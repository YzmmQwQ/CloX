// native_crash.cpp — catch fatal native signals and surface them through the
// app's crash reporter, mirroring what CrashHandler.kt does for Java crashes.
//
// Flow on a fatal signal (SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP):
//   1. write a readable report (signal, fault address, thread, backtrace via
//      _Unwind_Backtrace + dladdr) to the file Kotlin configured at install
//   2. best-effort JNI call to NativeCrashHandler.onNativeCrash(path), which
//      starts CrashActivity — it lives in the separate :crash process, so the
//      dialog survives this process dying
//   3. restore the previous handler and re-raise, so the system tombstone /
//      logcat backtrace still happen
//
// Honesty note on async-signal-safety: snprintf/dladdr/JNI are not officially
// signal-safe. Every production in-app native reporter (Crashlytics NDK,
// xCrash, …) accepts this: the process is dying anyway, the report file is
// written with raw open/write BEFORE the risky JNI attempt, and a leftover
// file is picked up on next launch (NativeCrashHandler.install) if the JNI
// notification never made it.
//
// ART's libsigchain interposes sigaction: the runtime's own SIGSEGV logic
// (null-deref → NullPointerException) keeps priority and only chains real
// crashes to us, so Java semantics are unaffected.

#include "native_crash.h"

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <semaphore.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#define LOG_TAG "CloX_NativeCrash"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kSignals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP };
constexpr int kNumSignals = sizeof(kSignals) / sizeof(kSignals[0]);
constexpr int kMaxFrames = 64;

JavaVM*   gVm = nullptr;
jclass    gHandlerClass = nullptr;        // com.yzmm.clox.crash.NativeCrashHandler
jmethodID gOnNativeCrash = nullptr;       // static onNativeCrash(String)
char      gReportPath[512] = {0};
char      gHeader[1024] = {0};            // version/device lines built in Kotlin
struct sigaction gOldHandlers[kNumSignals];
stack_t   gAltStack = {};
volatile sig_atomic_t gHandling = 0;

// Calling Java from the CRASHING thread's signal handler is unreliable: the
// signal may have interrupted the thread mid-JNI-transition, and any nested
// upcall then touches an invalid transition frame (CheckJNI aborts with
// "invalid JNI transition frame reference" — observed on-device). So the
// notification runs on this dedicated waiter thread, parked on a semaphore
// since install time; the handler only sem_post()s (async-signal-safe) and
// waits briefly for the launch to complete before re-raising.
sem_t     gNotifySem;
volatile sig_atomic_t gNotifyDone = 0;

void* notifierThread(void*) {
    prctl(PR_SET_NAME, "CloXCrashNotify", 0, 0, 0);
    JNIEnv* env = nullptr;
    if (!gVm || gVm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) return nullptr;
    for (;;) {
        while (sem_wait(&gNotifySem) != 0) {}   // retry on EINTR
        if (gHandlerClass && gOnNativeCrash) {
            jstring jpath = env->NewStringUTF(gReportPath);
            if (jpath) {
                env->CallStaticVoidMethod(gHandlerClass, gOnNativeCrash, jpath);
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(jpath);
            }
        }
        gNotifyDone = 1;
    }
}

const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default:      return "signal";
    }
}

// ── backtrace capture (libunwind's C API, available on all Android ABIs) ──────

struct BacktraceState {
    uintptr_t frames[kMaxFrames];
    int count = 0;
};

_Unwind_Reason_Code unwindCb(struct _Unwind_Context* ctx, void* arg) {
    auto* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        if (state->count >= kMaxFrames) return _URC_END_OF_STACK;
        state->frames[state->count++] = pc;
    }
    return _URC_NO_REASON;
}

// Append "  #NN pc 00xxxx  /path/lib.so (symbol+offset)" lines to buf.
size_t formatBacktrace(char* buf, size_t cap) {
    BacktraceState st;
    _Unwind_Backtrace(unwindCb, &st);
    size_t off = 0;
    // Skip the innermost frames (unwinder + this handler); keep it simple and
    // fixed — the report is for humans, tombstones stay authoritative.
    const int skip = 3;
    for (int i = skip; i < st.count && off + 160 < cap; i++) {
        uintptr_t pc = st.frames[i];
        Dl_info info;
        if (dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname) {
            uintptr_t rel = pc - reinterpret_cast<uintptr_t>(info.dli_fbase);
            if (info.dli_sname) {
                uintptr_t soff = pc - reinterpret_cast<uintptr_t>(info.dli_saddr);
                off += snprintf(buf + off, cap - off,
                    "  #%02d pc %08" PRIxPTR "  %s (%s+%" PRIuPTR ")\n",
                    i - skip, rel, info.dli_fname, info.dli_sname, soff);
            } else {
                off += snprintf(buf + off, cap - off,
                    "  #%02d pc %08" PRIxPTR "  %s\n",
                    i - skip, rel, info.dli_fname);
            }
        } else {
            off += snprintf(buf + off, cap - off,
                "  #%02d pc %08" PRIxPTR "  <unknown>\n", i - skip, pc);
        }
    }
    return off;
}

// ── the signal handler ────────────────────────────────────────────────────────

void restoreAndReraise(int sig, int idx) {
    if (idx >= 0) sigaction(sig, &gOldHandlers[idx], nullptr);
    else          signal(sig, SIG_DFL);
    raise(sig);
}

void crashHandler(int sig, siginfo_t* info, void* /*ucontext*/) {
    int idx = -1;
    for (int i = 0; i < kNumSignals; i++) if (kSignals[i] == sig) { idx = i; break; }

    // A crash inside the handler (or a second thread crashing) must not loop.
    if (gHandling) { restoreAndReraise(sig, idx); return; }
    gHandling = 1;

    static char report[16 * 1024];
    char thread[32] = "?";
    prctl(PR_GET_NAME, thread, 0, 0, 0);

    size_t off = snprintf(report, sizeof(report),
        "CloX Crash Report (native)\n"
        "==========================\n"
        "%s"
        "Signal:  %s (si_code %d)\n"
        "Fault:   %p\n"
        "Thread:  %s (tid %d)\n"
        "Epoch:   %ld\n"
        "\n"
        "Backtrace:\n",
        gHeader, signalName(sig), info ? info->si_code : 0,
        info ? info->si_addr : nullptr, thread, gettid(),
        static_cast<long>(time(nullptr)));
    off += formatBacktrace(report + off, sizeof(report) - off - 1);

    // 1. persist FIRST — raw open/write are signal-safe; if everything after
    //    this dies, NativeCrashHandler.install shows the report on next launch
    if (gReportPath[0]) {
        int fd = open(gReportPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            size_t written = 0;
            while (written < off) {
                ssize_t n = write(fd, report + written, off - written);
                if (n <= 0) break;
                written += static_cast<size_t>(n);
            }
            close(fd);
        }
    }
    LOGE("fatal %s — report written to %s", signalName(sig), gReportPath);

    // 2. best-effort: wake the notifier thread to pop the crash reporter (it
    //    runs in :crash, so it outlives us), and give it up to ~2s. Any
    //    failure here is fine — see step 1.
    gNotifyDone = 0;
    sem_post(&gNotifySem);
    for (int i = 0; i < 200 && !gNotifyDone; i++) {
        struct timespec ts = { 0, 10 * 1000 * 1000 };   // 10ms
        nanosleep(&ts, nullptr);
    }

    // 3. hand back to the previous handler chain → tombstone + process death
    restoreAndReraise(sig, idx);
}

} // namespace

// ── install (called once from NativeCrashHandler.install on the Kotlin side) ──

extern "C" JNIEXPORT void JNICALL
Java_com_yzmm_clox_crash_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject thiz, jstring reportPath, jstring header) {
    // `thiz` is the NativeCrashHandler singleton INSTANCE (nativeInstall is a
    // plain member on a Kotlin object, not @JvmStatic) — resolve its class for
    // the static onNativeCrash lookup.
    env->GetJavaVM(&gVm);
    jclass clazz = env->GetObjectClass(thiz);
    gHandlerClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    gOnNativeCrash = env->GetStaticMethodID(clazz, "onNativeCrash", "(Ljava/lang/String;)V");

    const char* path = env->GetStringUTFChars(reportPath, nullptr);
    if (path) {
        strncpy(gReportPath, path, sizeof(gReportPath) - 1);
        env->ReleaseStringUTFChars(reportPath, path);
    }
    const char* hdr = env->GetStringUTFChars(header, nullptr);
    if (hdr) {
        strncpy(gHeader, hdr, sizeof(gHeader) - 1);
        env->ReleaseStringUTFChars(header, hdr);
    }

    // Dedicated stack so a stack-overflow SIGSEGV can still be reported.
    if (!gAltStack.ss_sp) {
        gAltStack.ss_size = SIGSTKSZ * 2;
        gAltStack.ss_sp = new char[gAltStack.ss_size];
        gAltStack.ss_flags = 0;
        sigaltstack(&gAltStack, nullptr);

        sem_init(&gNotifySem, 0, 0);
        pthread_t notifier;
        pthread_create(&notifier, nullptr, notifierThread, nullptr);
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (int i = 0; i < kNumSignals; i++) {
        sigaction(kSignals[i], &sa, &gOldHandlers[i]);
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "native crash handler installed → %s", gReportPath);
}
