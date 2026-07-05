#pragma once

// Native (C++/signal) crash catcher — see native_crash.cpp.
// Installed from Kotlin via NativeCrashHandler.nativeInstall(); on a fatal
// signal it writes a readable report (signal, fault address, backtrace) to the
// pre-configured file and notifies NativeCrashHandler.onNativeCrash() so the
// CrashActivity (separate :crash process) can show it, then re-raises the
// signal for the normal system tombstone.
