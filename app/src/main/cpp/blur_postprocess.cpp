#include "blur_postprocess.h"
#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <android/log.h>
#include <atomic>
#include "base/Director.h"
#include "platform/RenderView.h"

#define BLUR_TAG "BlurPP"
#define BLOGI(...) __android_log_print(ANDROID_LOG_INFO,  BLUR_TAG, __VA_ARGS__)
#define BLOGE(...) __android_log_print(ANDROID_LOG_ERROR, BLUR_TAG, __VA_ARGS__)

// GLES3 enums not in gl2.h
#define GL_READ_FRAMEBUFFER   0x8CA8
#define GL_DRAW_FRAMEBUFFER   0x8CA9
#define GL_VERTEX_ARRAY_BINDING 0x85B5

namespace BlurPostProcess {

std::atomic<float> g_radius{0.0f};

// ── Runtime-loaded GLES3 functions ────────────────────────────────────────────

typedef void (GL_APIENTRY* FN_GenVAO)(GLsizei, GLuint*);
typedef void (GL_APIENTRY* FN_BindVAO)(GLuint);
typedef void (GL_APIENTRY* FN_DelVAO)(GLsizei, const GLuint*);
typedef void (GL_APIENTRY* FN_BlitFB)(GLint,GLint,GLint,GLint,GLint,GLint,GLint,GLint,GLbitfield,GLenum);

static FN_GenVAO  s_genVAO  = nullptr;
static FN_BindVAO s_bindVAO = nullptr;
static FN_DelVAO  s_delVAO  = nullptr;
static FN_BlitFB  s_blitFB  = nullptr;

static void loadGLES3() {
    auto get = [](const char* a, const char* b = nullptr) -> void* {
        void* p = (void*)eglGetProcAddress(a);
        if (!p && b) p = (void*)eglGetProcAddress(b);
        return p;
    };
    s_genVAO  = (FN_GenVAO) get("glGenVertexArrays",    "glGenVertexArraysOES");
    s_bindVAO = (FN_BindVAO)get("glBindVertexArray",    "glBindVertexArrayOES");
    s_delVAO  = (FN_DelVAO) get("glDeleteVertexArrays", "glDeleteVertexArraysOES");
    s_blitFB  = (FN_BlitFB) get("glBlitFramebuffer",    "glBlitFramebufferNV");
    BLOGI("VAO gen=%p bind=%p blit=%p", (void*)s_genVAO, (void*)s_bindVAO, (void*)s_blitFB);
}

// ── GL resources ──────────────────────────────────────────────────────────────

static bool  g_init = false;
static int   g_w = 0, g_h = 0;
static GLuint g_texA = 0, g_texB = 0;
static GLuint g_fboA = 0;   // blit target — texA as color attachment
static GLuint g_fboB = 0;   // H-blur target — texB as color attachment
static GLuint g_vbo  = 0;
static GLuint g_vao  = 0;
static GLuint g_progH = 0, g_progV = 0;
static GLint  g_uRadiusH = -1, g_uStepH = -1;
static GLint  g_uRadiusV = -1, g_uStepV = -1;

// ── Shaders ───────────────────────────────────────────────────────────────────

static const char* kVert = R"glsl(#version 300 es
in vec2 a_pos;
in vec2 a_uv;
out vec2 v_uv;
void main() {
    gl_Position = vec4(a_pos, 0.0, 1.0);
    v_uv = a_uv;
}
)glsl";

static const char* kFragH = R"glsl(#version 300 es
precision mediump float;
in vec2 v_uv;
out vec4 o_color;
uniform sampler2D u_tex;
uniform float u_radius;
uniform float u_step;
void main() {
    int r = max(1, int(u_radius));
    vec4 sum = vec4(0.0);
    for (int i = -r; i <= r; i++)
        sum += texture(u_tex, v_uv + vec2(float(i) * u_step, 0.0));
    o_color = sum / float(2 * r + 1);
}
)glsl";

static const char* kFragV = R"glsl(#version 300 es
precision mediump float;
in vec2 v_uv;
out vec4 o_color;
uniform sampler2D u_tex;
uniform float u_radius;
uniform float u_step;
void main() {
    int r = max(1, int(u_radius));
    vec4 sum = vec4(0.0);
    for (int i = -r; i <= r; i++)
        sum += texture(u_tex, v_uv + vec2(0.0, float(i) * u_step));
    o_color = sum / float(2 * r + 1);
}
)glsl";

// ── Helpers ───────────────────────────────────────────────────────────────────

static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512]; glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
        BLOGE("shader: %s", buf);
        glDeleteShader(s); return 0;
    }
    return s;
}

static GLuint linkProgram(const char* vert, const char* frag) {
    GLuint vs = compileShader(GL_VERTEX_SHADER,   vert);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, frag);
    if (!vs || !fs) { glDeleteShader(vs); glDeleteShader(fs); return 0; }
    GLuint p = glCreateProgram();
    glBindAttribLocation(p, 0, "a_pos");
    glBindAttribLocation(p, 1, "a_uv");
    glAttachShader(p, vs); glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok = 0; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512]; glGetProgramInfoLog(p, sizeof(buf), nullptr, buf);
        BLOGE("link: %s", buf);
        glDeleteProgram(p); return 0;
    }
    // Save/restore program — Axmol's state cache must not see our program
    GLint prevProg = 0; glGetIntegerv(GL_CURRENT_PROGRAM, &prevProg);
    glUseProgram(p);
    glUniform1i(glGetUniformLocation(p, "u_tex"), 0);
    glUseProgram((GLuint)prevProg);
    return p;
}

// GL_RGBA8 texture — caller must save/restore GL_TEXTURE_BINDING_2D
static GLuint makeTexture(int w, int h) {
    GLuint t; glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, 0x8058 /*GL_RGBA8*/, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

// caller must save/restore GL_FRAMEBUFFER_BINDING
static GLuint makeFBO(GLuint tex) {
    GLuint fbo; glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        BLOGE("FBO %u incomplete", fbo);
    return fbo;
}

// ── Init / teardown ───────────────────────────────────────────────────────────

static void doInit(int w, int h) {
    loadGLES3();
    g_w = w; g_h = h;

    // ── Save ALL GL state we will touch so Axmol's cache stays consistent ─────
    GLint saveTex = 0;  glGetIntegerv(GL_TEXTURE_BINDING_2D,   &saveTex);
    GLint saveFbo = 0;  glGetIntegerv(GL_FRAMEBUFFER_BINDING,  &saveFbo);
    GLint saveVao = 0;  glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &saveVao);
    GLint saveArr = 0;  glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &saveArr);

    g_texA = makeTexture(w, h);
    g_texB = makeTexture(w, h);
    glBindTexture(GL_TEXTURE_2D, (GLuint)saveTex);   // restore after makeTexture

    g_fboA = makeFBO(g_texA);
    g_fboB = makeFBO(g_texB);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)saveFbo); // restore after makeFBO

    static const float verts[] = {
        -1.f, -1.f,  0.f, 0.f,
         1.f, -1.f,  1.f, 0.f,
        -1.f,  1.f,  0.f, 1.f,
         1.f,  1.f,  1.f, 1.f,
    };
    if (s_genVAO && s_bindVAO) {
        s_genVAO(1, &g_vao);
        s_bindVAO(g_vao);
        glGenBuffers(1, &g_vbo);
        glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4*sizeof(float), (void*)0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4*sizeof(float), (void*)(2*sizeof(float)));
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, (GLuint)saveArr); // restore before unbinding VAO
        s_bindVAO((GLuint)saveVao);
    } else {
        glGenBuffers(1, &g_vbo);
        glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, (GLuint)saveArr); // restore
    }

    // linkProgram saves/restores GL_CURRENT_PROGRAM internally
    g_progH = linkProgram(kVert, kFragH);
    g_progV = linkProgram(kVert, kFragV);
    if (g_progH) { g_uRadiusH = glGetUniformLocation(g_progH, "u_radius"); g_uStepH = glGetUniformLocation(g_progH, "u_step"); }
    if (g_progV) { g_uRadiusV = glGetUniformLocation(g_progV, "u_radius"); g_uStepV = glGetUniformLocation(g_progV, "u_step"); }

    g_init = true;
    BLOGI("init %dx%d vao=%u blit=%p", w, h, g_vao, (void*)s_blitFB);
}

static void teardown() {
    if (!g_init) return;
    glDeleteTextures(1, &g_texA);       g_texA = 0;
    glDeleteTextures(1, &g_texB);       g_texB = 0;
    glDeleteFramebuffers(1, &g_fboA);   g_fboA = 0;
    glDeleteFramebuffers(1, &g_fboB);   g_fboB = 0;
    glDeleteBuffers(1, &g_vbo);         g_vbo = 0;
    if (g_vao && s_delVAO) { s_delVAO(1, &g_vao); g_vao = 0; }
    if (g_progH) { glDeleteProgram(g_progH); g_progH = 0; }
    if (g_progV) { glDeleteProgram(g_progV); g_progV = 0; }
    g_init = false;
}

// Called when the EGL context is destroyed (screen off / app background).
// We cannot call glDelete* — the handles are already invalid.
// Just zero everything so doInit() recreates fresh on next use.
void onContextLost() {
    g_texA = g_texB = g_fboA = g_fboB = g_vbo = g_vao = 0;
    g_progH = g_progV = 0;
    g_uRadiusH = g_uStepH = g_uRadiusV = g_uStepV = -1;
    s_genVAO = nullptr; s_bindVAO = nullptr; s_delVAO = nullptr;
    s_blitFB = nullptr;
    g_init = false;
    BLOGI("context lost — resources invalidated");
}

static void drawQuad() {
    if (g_vao && s_bindVAO) {
        s_bindVAO(g_vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    } else {
        glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4*sizeof(float), (void*)0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4*sizeof(float), (void*)(2*sizeof(float)));
        glEnableVertexAttribArray(1);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
}

// ── Public ────────────────────────────────────────────────────────────────────

void applyIfNeeded() {
    float r = g_radius.load(std::memory_order_relaxed);
    if (r <= 0.0f) return;

    auto fs = ax::Director::getInstance()->getRenderView()->getFrameSize();
    int w = (int)fs.width, h = (int)fs.height;
    if (w <= 0 || h <= 0) return;

    if (!g_init || g_w != w || g_h != h) { teardown(); doInit(w, h); }
    if (!g_progH || !g_progV || !s_blitFB) {
        if (!s_blitFB) BLOGE("glBlitFramebuffer not available — REALTIME blur unsupported");
        return;
    }

    // ── Save GL state ─────────────────────────────────────────────────────────
    GLint prevFbo  = 0; glGetIntegerv(GL_FRAMEBUFFER_BINDING,  &prevFbo);
    static GLint s_loggedFbo = -1;
    if (prevFbo != s_loggedFbo) { BLOGI("Axmol FBO=%d", prevFbo); s_loggedFbo = prevFbo; }
    GLint prevProg = 0; glGetIntegerv(GL_CURRENT_PROGRAM,      &prevProg);
    GLint prevVao  = 0; glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prevVao);
    GLint prevTex  = 0; glGetIntegerv(GL_TEXTURE_BINDING_2D,   &prevTex);
    GLint prevUnit = 0; glGetIntegerv(GL_ACTIVE_TEXTURE,       &prevUnit);
    GLint prevVp[4]= {}; glGetIntegerv(GL_VIEWPORT,            prevVp);
    GLboolean blendOn = glIsEnabled(GL_BLEND);
    GLboolean depthOn = glIsEnabled(GL_DEPTH_TEST);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glViewport(0, 0, w, h);
    glActiveTexture(GL_TEXTURE0);

    // Pass 1: blit Axmol's render FBO (prevFbo, may not be 0) → g_fboA (texA).
    // Using prevFbo as source correctly handles devices where Axmol's default
    // FBO is not 0 (captured via glGetIntegerv(GL_FRAMEBUFFER_BINDING) at init).
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)prevFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_fboA);
    s_blitFB(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    {
        GLenum e = glGetError();
        if (e) {
            BLOGE("blit error 0x%x (srcFBO=%d)", e, prevFbo);
            if (e == 0x506 /* GL_INVALID_FRAMEBUFFER_OPERATION */) {
                // EGL context was lost and recreated — reinit on next frame
                onContextLost();
            }
            // Restore state then bail; doInit will run on the next applyIfNeeded call
            if (s_bindVAO) s_bindVAO((GLuint)prevVao);
            glUseProgram((GLuint)prevProg);
            glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, (GLuint)prevTex);
            glActiveTexture((GLenum)prevUnit);
            glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
            if (blendOn) glEnable(GL_BLEND);
            if (depthOn) glEnable(GL_DEPTH_TEST);
            return;
        }
    }

    // Pass 2: horizontal blur texA → g_fboB (texB)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fboB);
    glUseProgram(g_progH);
    glUniform1f(g_uRadiusH, r);
    glUniform1f(g_uStepH, 1.0f / (float)w);
    glBindTexture(GL_TEXTURE_2D, g_texA);
    drawQuad();

    // Pass 3: vertical blur texB → Axmol's render FBO (same as source)
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
    glUseProgram(g_progV);
    glUniform1f(g_uRadiusV, r);
    glUniform1f(g_uStepV, 1.0f / (float)h);
    glBindTexture(GL_TEXTURE_2D, g_texB);
    drawQuad();

    // ── Restore GL state ──────────────────────────────────────────────────────
    if (s_bindVAO) s_bindVAO((GLuint)prevVao);
    glUseProgram((GLuint)prevProg);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, (GLuint)prevTex);
    glActiveTexture((GLenum)prevUnit);
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    if (blendOn) glEnable(GL_BLEND);
    if (depthOn) glEnable(GL_DEPTH_TEST);
}

}  // namespace BlurPostProcess
