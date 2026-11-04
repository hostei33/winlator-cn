#include "gl_context.h"
#include "gl_renderer.h"
#include "gl_dsa.h"
#include "sysvshared_memory.h"
#include "request_handler.h"

extern EGLContext globalEGLContext;

pthread_mutex_t glx_context_mutex = PTHREAD_MUTEX_INITIALIZER;

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->getWindowSize = (*env)->GetMethodID(env, cls, "getWindowSize", "(I)[S");
    jmethods->updateWindowContent = (*env)->GetMethodID(env, cls, "updateWindowContent", "(ISSZ)Z");
    jmethods->getGLXContextPtr = (*env)->GetMethodID(env, cls, "getGLXContextPtr", "(II)J");
}

static void getWindowSize(JMethods* jmethods, int windowId, short* outWidth, short* outHeight) {
    jshortArray windowSize = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj, jmethods->getWindowSize, windowId);
    jshort* windowSizePtr = (*jmethods->env)->GetShortArrayElements(jmethods->env, windowSize, 0);
    *outWidth = windowSizePtr[0];
    *outHeight = windowSizePtr[1];
    (*jmethods->env)->ReleaseShortArrayElements(jmethods->env, windowSize, windowSizePtr, JNI_ABORT);
}

static void createDisplayBuffer(GLContext* context) {
    // 保存客户端绑定状态：下面的 bind(GL_FRAMEBUFFER, 0) 会 ARRAYS_FILL 把三槽
    // 覆盖成 0（=默认帧缓冲），随后必须按入口值恢复 READ 槽与 slot0，否则在
    // FAILED 重建路径上客户端缓存指向自己的 FBO/0、不会重绑，READ 槽一旦被污染，
    // present 的 blit 就再次成为同 FBO 重叠拷贝被 GLES 拒绝（黑屏）。
    // 注：DRAW 槽无需恢复——调用方随后必然经 GLRenderer_setDrawBuffer(GL_BACK)
    // 把 DRAW 绑回默认缓冲。入口槽值只会是 0 或客户端自己的 FBO id（槽里从不存
    // displayBuffer 私有 id），恢复不会触发僵尸 entry 复活。
    GLuint saved[MAX_FRAMEBUFFER_TARGETS];
    memcpy(saved, currentRenderer->clientState.framebuffer, sizeof(saved));

    currentRenderer->displayBuffer = GLFramebuffer_create();
    GLFramebuffer_bind(GL_FRAMEBUFFER, 0);  // 0→displayBuffer 映射在真实层完成，槽记 0
    GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, context->displayBufAttachment.texture, 0);
    GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, context->displayBufAttachment.renderbuffer, 0);

    GLFramebuffer_bind(GL_READ_FRAMEBUFFER, saved[indexOfGLTarget(GL_READ_FRAMEBUFFER)]);
    currentRenderer->clientState.framebuffer[0] = saved[0];
}

static void destroyDisplayBuffer() {
    if (currentRenderer && currentRenderer->displayBuffer > 0) {
        // 先清零再 delete：GLFramebuffer_delete 会把被删对象所在的槽重绑为 0，若此刻
        // displayBuffer 仍是旧值，bind 的 0→displayBuffer 映射会自指已删 FBO，槽位记录
        // 悬垂（swap 失败重建路径 resize/转屏可触发）。先清零则重绑记录为 0，之后
        // bind(0) 经映射自然指向新 displayBuffer，"默认帧缓冲"语义无缝迁移。
        GLuint framebuffer = currentRenderer->displayBuffer;
        currentRenderer->displayBuffer = 0;
        GLFramebuffer_delete(framebuffer);
    }
}

static void createDisplayBufAttachment(GLContext* context) {
    short width = currentRenderer->displaySize[0];
    short height = currentRenderer->displaySize[1];

    if (context->displayBufAttachment.texture == 0) {
        // 必须用驱动 glGenTextures 分配（与下方 renderbuffer 的 glGenRenderbuffers
        // 同一分配器）：客户端纹理句柄走 gladio 计数器、真实纹理名由 createNamedTexture
        // 内部另行 glGenTextures，两套都是虚拟映射；若把 GLTexture_create 的计数器
        // 句柄直接当真实纹理名 glBindTexture，会隐式创建小号真实对象，与 Java 合成器
        // （同一驱动分配器）的壁纸/UI 纹理撞成同一对象——displayBuffer 内容会直接
        // 写进壁纸纹理（壁纸黑且不可恢复）。
        glGenTextures(1, &context->displayBufAttachment.texture);
        glBindTexture(GL_TEXTURE_2D, context->displayBufAttachment.texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, PREFERRED_FRAMEBUFFER_FORMAT, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    if (context->displayBufAttachment.renderbuffer == 0) {
        glGenRenderbuffers(1, &context->displayBufAttachment.renderbuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, context->displayBufAttachment.renderbuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, PREFERRED_RENDERBUFFER_FORMAT, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }
}

static void destroyDisplayBufAttachment(GLContext* context) {
    if (context->displayBufAttachment.texture > 0) {
        glDeleteTextures(1, &context->displayBufAttachment.texture);
        context->displayBufAttachment.texture = 0;
    }

    if (context->displayBufAttachment.renderbuffer > 0) {
        glDeleteRenderbuffers(1, &context->displayBufAttachment.renderbuffer);
        context->displayBufAttachment.renderbuffer = 0;
    }
}

static void setCurrentRenderWindow(GLContext* context, int windowId) {
    if (windowId == 0) return;
    JMethods* jmethods = &context->jmethods;

    short width;
    short height;
    getWindowSize(jmethods, windowId, &width, &height);
    if (width == 0 || height == 0) return;

    bool resized = currentRenderer->displaySize[0] != width || currentRenderer->displaySize[1] != height;
    context->currentWindowId = windowId;

    // displayBuffer 已经就绪且尺寸没变：直接返回，并且不要清空窗口内容，
    // 否则每次 MakeCurrent 都会销毁窗口纹理，使窗口化下出现黑屏。
    if (currentRenderer->displayBuffer > 0 && !resized) return;

    // 注意：这里不能清空窗口内容（Java 侧已移除 clearWindowContent）。窗口化 ddraw
    // 游戏的 present 走 wined3d 的 GDI 路径（不经过 glXSwapBuffers），窗口内容靠
    // X11 2D 绘图更新 drawable 的 CPU data；清空 data 会永久摧毁该窗口的 2D 显示
    // 路径导致黑屏。GL 接管窗口由 Java 端 updateWindowContent 在首次成功拷贝时
    // 自行 setData(null) 完成，无需在此提前清除。

    currentRenderer->displaySize[0] = width;
    currentRenderer->displaySize[1] = height;

    if (currentRenderer->displayBuffer == 0) {
        destroyDisplayBufAttachment(context);
        createDisplayBufAttachment(context);
        createDisplayBuffer(context);
    }
    else {
        // 尺寸变化只换 attachment、保持 displayBuffer FBO 恒定，并且【绝不能】
        // 清空 clientState.framebuffer：客户端(wined3d)有自己的状态缓存，gladio 侧
        // 缓存被清成 0（bind 时 0 会映射成 displayBuffer）而客户端以为仍绑着 back
        // buffer 不再重绑，会使 present 的 glBlitFramebuffer 源与目标同为
        // displayBuffer（同 FBO 重叠拷贝），被 GLES 以 GL_INVALID_OPERATION 拒绝，
        // 画面恒黑。重建 FBO 本体同样危险：旧 id 被删后客户端缓存仍指向它。
        destroyDisplayBufAttachment(context);
        createDisplayBufAttachment(context);

        GLuint savedFB0 = currentRenderer->clientState.framebuffer[0];
        GLuint savedReadFB = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_READ_FRAMEBUFFER)];
        GLFramebuffer_bind(GL_FRAMEBUFFER, 0);
        GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, context->displayBufAttachment.texture, 0);
        GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, context->displayBufAttachment.renderbuffer, 0);
        // DRAW 槽无需恢复：随后 GLRenderer_setDrawBuffer(GL_BACK) 必然把 DRAW 绑回
        // 默认缓冲。READ 槽必须恢复。
        GLFramebuffer_bind(GL_READ_FRAMEBUFFER, savedReadFB);
        // 合并槽不通过 bind 恢复（会 ARRAYS_FILL 波及 DRAW 槽），直接写回缓存值，
        // 保持与客户端"最后一次 GL_FRAMEBUFFER 绑定"的记录一致。
        currentRenderer->clientState.framebuffer[0] = savedFB0;
    }

    GLRenderer_setDrawBuffer(currentRenderer, GL_BACK);

    GLTexture* texture = GLTexture_getBound(GL_TEXTURE_2D);
    glBindTexture(GL_TEXTURE_2D, texture ? texture->id : 0);

    glViewport(0, 0, width, height);
    glScissor(0, 0, width, height);
}

static void swapDisplayBuffers(GLContext* context, int drawableId) {
    GLuint drawFramebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_DRAW_FRAMEBUFFER)];
    GLuint readFramebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_READ_FRAMEBUFFER)];

    // 只同步 DRAW 槽，【绝不】用 bind(GL_FRAMEBUFFER)：其内部 ARRAYS_FILL 会把
    // READ 槽一并覆盖成 DRAW 的值（present 时即 0/默认缓冲）。客户端(wined3d)
    // 的状态缓存认为 READ 仍是自己的 back buffer、不会重绑，之后 present 的
    // glBlitFramebuffer 源与目标同为 displayBuffer（同 FBO 重叠拷贝），被 GLES
    // 以 GL_INVALID_OPERATION 拒绝 → 画面恒黑（桌面窗口同理）。
    // 槽值==0 表示客户端 DRAW 目标正是默认缓冲（真实层已绑 displayBuffer），无需动作。
    if (drawFramebuffer != 0)
        GLFramebuffer_bind(GL_DRAW_FRAMEBUFFER, drawFramebuffer);

    // glXSwapBuffers 提交的是默认帧缓冲，而 Java 端 updateWindowContent 内部用
    // glCopyTexImage2D 从"当前 GL_READ_FRAMEBUFFER"读取像素。客户端(wined3d 等)在
    // present 时往往把 GL_READ_FRAMEBUFFER 留在自己的离屏 back buffer FBO 上，
    // 那份内容是 top-down 的且尺寸不一定等于窗口，会造成全屏画面上下颠倒、窗口化黑屏。
    // 因此这里强制把读源切到默认缓冲（0→displayBuffer），拷贝完成后再恢复客户端
    // 原来的读绑定。
    if (readFramebuffer != 0) GLFramebuffer_bind(GL_READ_FRAMEBUFFER, 0);

    JMethods* jmethods = &context->jmethods;
    bool result = (*jmethods->env)->CallBooleanMethod(jmethods->env, jmethods->obj, jmethods->updateWindowContent, drawableId, currentRenderer->displaySize[0], currentRenderer->displaySize[1], JNI_TRUE);
    if (result) {
        // Java 端 present 拷贝（allocateTexture/copyFromReadBuffer）会把活动纹理单元
        // 切到 0 并在结尾清空其 GL_TEXTURE_2D 绑定。客户端（wined3d）缓存了活动单元，
        // 单元号不变时不会重发 glActiveTexture，因此必须按客户端状态先还原单元 0 的
        // 绑定、再把实际活动单元切回缓存值，否则服务端真实活动单元与客户端记录永久
        // 错位，多纹理游戏的贴图会绑丢。
        GLTexture* unit0Texture = currentRenderer->clientState.texture[0][indexOfGLTarget(GL_TEXTURE_2D)];
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, unit0Texture ? unit0Texture->id : 0);
        glActiveTexture(GL_TEXTURE0 + currentRenderer->clientState.activeTexture);

        GLTexture* texture = GLTexture_getBound(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, texture ? texture->id : 0);
    }
    else {
        destroyDisplayBuffer();
        destroyDisplayBufAttachment(context);
        setCurrentRenderWindow(context, drawableId);
    }

    // 出口自愈：任何内部路径（含 FAILED 重建）若把 READ 槽改成了非入口值，
    // 一律恢复为客户端入口时的绑定，保证与客户端(wined3d)的状态缓存一致。
    if (currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_READ_FRAMEBUFFER)] != readFramebuffer)
        GLFramebuffer_bind(GL_READ_FRAMEBUFFER, readFramebuffer);
}

// ddraw 等客户端在窗口模式下会把 primary(front buffer) 当作绘制目标（wined3d 用
// glBlitFramebuffer 拷到 FBO 0 再 glFlush），并且从不调用 glXSwapBuffers。
// 真实 X11 语义下 front buffer 是即画即显的，这里在 flush/blit 边界补上这一步：
// 若当前 draw framebuffer 正是默认帧缓冲（槽记 0，真实层即 displayBuffer），
// 就把内容推送给窗口合成器。
void gd_presentIfFrontBufferBound(GLContext* context) {
    if (!currentRenderer || currentRenderer->displayBuffer == 0 || context->currentWindowId == 0) return;

    GLuint drawFramebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_DRAW_FRAMEBUFFER)];
    if (drawFramebuffer != 0) return;

    // 节流时间戳挂在 GLContext 上而非函数级 static：static 跨线程共享，多 GL 线程
    // （wined3d CS 线程 + GDI/ddraw 线程）会互抢 6ms 窗口丢帧，且非原子读写是数据竞争。
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t nowNs = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
    if (context->lastPresentNs != 0 && nowNs - context->lastPresentNs < 6000000ull) return;
    context->lastPresentNs = nowNs;

    swapDisplayBuffers(context, context->currentWindowId);
}

static bool isCanDrawImmediate(short requestCode) {
    return requestCode == REQUEST_CODE_GL_BEGIN ||
           requestCode == REQUEST_CODE_GL_END ||
           requestCode == REQUEST_CODE_GL_DRAW_ARRAYS ||
           requestCode == REQUEST_CODE_GL_DRAW_ELEMENTS ||
           requestCode == REQUEST_CODE_GL_ENABLE_CLIENT_STATE ||
           requestCode == REQUEST_CODE_GL_DISABLE_CLIENT_STATE ||
           requestCode == REQUEST_CODE_GL_VERTEX_POINTER ||
           requestCode == REQUEST_CODE_GL_COLOR_POINTER ||
           requestCode == REQUEST_CODE_GL_NORMAL_POINTER ||
           requestCode == REQUEST_CODE_GL_TEX_COORD_POINTER ||
           requestCode == REQUEST_CODE_GL_VERTEX4F ||
           requestCode == REQUEST_CODE_GL_COLOR4F ||
           requestCode == REQUEST_CODE_GL_NORMAL3F ||
           requestCode == REQUEST_CODE_GL_TEX_COORD4F ||
           requestCode == REQUEST_CODE_GL_MULTI_TEX_COORD4F ||
           requestCode == REQUEST_CODE_GL_ARRAY_ELEMENT ? false : true;
}

static void* requestHandlerThread(void* param) {
    GLContext* context = param;
    loadJMethods(&context->jmethods);
    short requestCode;

    while (context->running) {
        if (!gl_recv(context->serverRing, &requestCode, &context->inputBuffer)) break;

        if (isCanDrawImmediate(requestCode)) GLRenderer_drawImmediate(currentRenderer);

        switch (requestCode) {
            case REQUEST_CODE_SET_CURRENT_RENDER_WINDOW: {
                int windowId = ArrayBuffer_getInt(&context->inputBuffer);
                int contextId = ArrayBuffer_getInt(&context->inputBuffer);
                JMethods* jmethods = &context->jmethods;
                GLXContext* glxContext = (GLXContext*)(*jmethods->env)->CallLongMethod(jmethods->env, jmethods->obj, jmethods->getGLXContextPtr, context->clientFd, contextId);

                if (glxContext && context->glxContext != glxContext) {
                    destroyDisplayBuffer();
                    eglMakeCurrent(eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_NO_SURFACE, EGL_NO_SURFACE, glxContext->eglContext);
                    context->glxContext = glxContext;
                    currentRenderer = &glxContext->renderer;
                    GLRenderer_resetFrameCount(currentRenderer);
                }

                setCurrentRenderWindow(context, windowId);
                gl_send(context->clientRing, REQUEST_CODE_SET_CURRENT_RENDER_WINDOW, NULL, 0);
                break;
            }
            case REQUEST_CODE_SWAP_DISPLAY_BUFFERS: {
                int drawableId = ArrayBuffer_getInt(&context->inputBuffer);
                currentRenderer->frameCount++;
                swapDisplayBuffers(context, drawableId);
                gl_send(context->clientRing, REQUEST_CODE_SWAP_DISPLAY_BUFFERS, NULL, 0);
                break;
            }
            default: {
                if (requestCode >= REQUEST_CODE_GL_DSA_START && requestCode < REQUEST_CODE_GL_CALL_START) {
                    handleDSARequest(context, requestCode);
                    break;
                }

#if IS_DEBUG_ENABLED(DEBUG_MODE_HANDLE_REQUEST)
                println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBuffer.size);
#endif

                HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
                if (handleRequestFunc) handleRequestFunc(context);
                break;
            }
        }

#if IS_DEBUG_ENABLED(DEBUG_MODE_GL_ERROR)
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) println("gladio: glError %s %x", requestCodeToString(requestCode), error);
#endif
    }

    destroyDisplayBuffer();
    destroyDisplayBufAttachment(context);
    if (context->glxContext) {
        eglMakeCurrent(eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        context->glxContext = NULL;
    }
    (*context->jmethods.jvm)->DetachCurrentThread(context->jmethods.jvm);
    return NULL;
}

GLContext* createGLContext(JNIEnv* env, jobject obj, int clientFd) {
    GLContext* context = calloc(1, sizeof(GLContext));
    context->clientFd = clientFd;

    int shmFds[2];
    shmFds[0] = ashmemCreateRegion("gl-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    shmFds[1] = ashmemCreateRegion("gl-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) goto error;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) goto error;

    int result = send_fds(clientFd, shmFds, 2, NULL, 0);
    if (result < 0) goto error;

    (*env)->GetJavaVM(env, &context->jmethods.jvm);
    context->jmethods.obj = (*env)->NewGlobalRef(env, obj);

    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);
    context->running = true;
    pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context);

    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    return context;

error:
    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    MEMFREE(context);
    return NULL;
}

void destroyGLContext(JNIEnv* env, GLContext* context) {
    context->running = false;

    if (context->requestHandlerThread) {
        RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
        RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);
        pthread_join(context->requestHandlerThread, NULL);

        ThreadPool_destroy(context->threadPool);
        context->threadPool = NULL;

        context->requestHandlerThread = 0;
        RingBuffer_free(context->serverRing);
        RingBuffer_free(context->clientRing);
    }

    if (context->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, context->jmethods.obj);
        context->jmethods.obj = NULL;
    }

    ArrayBuffer_free(&context->inputBuffer);
    ArrayBuffer_free(&context->outputBuffer);

    free(context);
}

GLXContext* createGLXContext(int contextId, GLXContext* sharedContext) {
    static const EGLint confAttribList[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    static const EGLint ctxAttribList[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    EGLBoolean success;

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglDisplay) return NULL;

    EGLint major, minor;
    success = eglInitialize(eglDisplay, &major, &minor);
    if (!success) return NULL;

    int numConfigs;
    EGLConfig eglConfig;
    success = eglChooseConfig(eglDisplay, confAttribList, &eglConfig, 1, &numConfigs);
    if (!success || numConfigs != 1) return NULL;

    EGLContext eglContext = eglCreateContext(eglDisplay, eglConfig, sharedContext ? sharedContext->eglContext : globalEGLContext, ctxAttribList);

    GLXContext* context = calloc(1, sizeof(GLXContext));
    context->eglContext = eglContext;
    context->renderer.contextId = contextId;
    GLVertexArrayObject_setBound(&context->renderer.clientState, 0);
    GLClientState_init(&context->renderer.clientState, sharedContext ? &sharedContext->renderer.clientState : NULL);

    GLX_CONTEXT_LOCK();
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context->eglContext);
    GLRenderer_initOnEGLContext(&context->renderer);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    GLX_CONTEXT_UNLOCK();
    return context;
}

void destroyGLXContext(GLXContext* context) {
    GLX_CONTEXT_LOCK();
    GLClientState_destroy(&context->renderer.clientState);
    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context->eglContext);
    GLRenderer_destroy(&context->renderer);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    GLX_CONTEXT_UNLOCK();
    eglDestroyContext(eglDisplay, context->eglContext);
}

static void internalReadVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx, ArrayBuffer* dstBuffer, float* constValue) {
#define PARSE_VALUESF(glType, divVal) \
    const glType* values = srcValues; \
    for (int i = 0; i < vertexAttrib->size; i++) dstValues[i] = arrayIdx == COLOR_ARRAY_INDEX ? (float)values[i] / divVal : (float)values[i]

    GLClientState* clientState = &currentRenderer->clientState;
    if (!clientState->vao->attribs[arrayIdx].state) {
        if (constValue && dstBuffer->position > 0) {
            if (arrayIdx == NORMAL_ARRAY_INDEX) {
                ArrayBuffer_putBytes(dstBuffer, constValue, 3 * sizeof(float));
            }
            else ArrayBuffer_putBytes(dstBuffer, constValue, 4 * sizeof(float));
        }
        return;
    }

    GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[arrayIdx];

    float* dstValues = NULL;
    if (arrayIdx == NORMAL_ARRAY_INDEX) {
        dstValues = ArrayBuffer_putFloat3(dstBuffer, 0.0f, 0.0f, 1.0f);
    }
    else dstValues = ArrayBuffer_putFloat4(dstBuffer, 0.0f, 0.0f, 0.0f, 1.0f);
    dstBuffer->position++;

    void* srcValues;
    if (vertexAttrib->pointer) {
        srcValues = vertexAttrib->pointer + (elementIdx - clientState->indexStart) * vertexAttrib->stride;
    }
    else srcValues = ArrayBuffer_getBytes(&context->inputBuffer, vertexAttrib->stride);

    switch (vertexAttrib->type) {
        case GL_FLOAT: {
            memcpy(dstValues, srcValues, vertexAttrib->size * sizeof(float));
            break;
        }
        case GL_HALF_FLOAT: {
            const GLhalf* values = srcValues;
            _Float16 value;
            for (int i = 0; i < vertexAttrib->size; i++) {
                memcpy(&value, &values[i], sizeof(value));
                dstValues[i] = (float)value;
            }
            break;
        }
        case GL_BYTE: {
            PARSE_VALUESF(GLbyte, INT8_MAX);
            break;
        }
        case GL_UNSIGNED_BYTE: {
            PARSE_VALUESF(GLubyte, UINT8_MAX);
            break;
        }
        case GL_SHORT: {
            PARSE_VALUESF(GLshort, INT16_MAX);
            break;
        }
        case GL_UNSIGNED_SHORT: {
            PARSE_VALUESF(GLushort, UINT16_MAX);
            break;
        }
        case GL_INT: {
            PARSE_VALUESF(GLint, (double)INT32_MAX);
            break;
        }
        case GL_UNSIGNED_INT: {
            PARSE_VALUESF(GLuint, (double)UINT32_MAX);
            break;
        }
        case GL_DOUBLE: {
            PARSE_VALUESF(GLdouble, 1);
            break;
        }
    }

#undef PARSE_VALUESF
}

void readCommandBuffer(GLContext* context) {
    if (ArrayBuffer_available(&context->inputBuffer) == 0) {
        GLRenderer_endImmediate(currentRenderer);
        return;
    }

    GLenum mode = ArrayBuffer_getInt(&context->inputBuffer);
    int indexStart = ArrayBuffer_getInt(&context->inputBuffer);
    int indexCount = ArrayBuffer_getInt(&context->inputBuffer);
    int bufferSize = ArrayBuffer_getInt(&context->inputBuffer);

    if (bufferSize == 0) return;

    GLClientState* clientState = &currentRenderer->clientState;
    clientState->indexStart = indexStart;
    if (indexCount > 0) {
        for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
            if (clientState->vao->attribs[i].state) {
                clientState->vao->attribs[i].pointer = context->inputBuffer.buffer + context->inputBuffer.position;
                context->inputBuffer.position += indexCount * clientState->vao->attribs[i].stride;
            }
        }
    }
    else ArrayBuffer_rewind(&context->inputBuffer);

    ENSURE_ARRAY_CAPACITY(context->inputBuffer.size + bufferSize, context->inputBuffer.capacity, context->inputBuffer.buffer, 1);
    RingBuffer_read(context->serverRing, context->inputBuffer.buffer + context->inputBuffer.position, bufferSize);
    context->inputBuffer.size += bufferSize;

    // 16-bit indices: at most one vertex per 6 bytes of commands (glArrayElement)
    GLRenderer_reserveBatchVertices(currentRenderer, bufferSize / 6);
    GLRenderer_beginImmediate(currentRenderer, mode);
    while (ArrayBuffer_available(&context->inputBuffer) > 0) {
        short requestCode = ArrayBuffer_getShort(&context->inputBuffer);
        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (!handleRequestFunc) break;
        handleRequestFunc(context);
    }
    GLRenderer_endImmediate(currentRenderer);

    for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
        if (clientState->vao->attribs[i].state) clientState->vao->attribs[i].pointer = NULL;
    }
    clientState->indexStart = 0;
}

void readVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx) {
    switch (arrayIdx) {
        case POSITION_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.vertices, NULL);
            break;
        case COLOR_ARRAY_INDEX: {
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.colors, currentRenderer->state.color);
            break;
        }
        case NORMAL_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.normals, currentRenderer->state.normal);
            break;
        default:
            if (arrayIdx >= TEXCOORD_ARRAY_INDEX) {
                int index = arrayIdx - TEXCOORD_ARRAY_INDEX;
                internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.texCoords[index], currentRenderer->state.texCoords[index]);
            }
            break;
    }
}

bool readUnboundVertexArrays(GLContext* context, GLenum drawMode, int drawCount, void** outIndices, GLenum indexType) {
    if (indexType != GL_NONE) {
        if (GLBuffer_getBound(GL_ELEMENT_ARRAY_BUFFER)) {
            *outIndices = (void*)(uint64_t)ArrayBuffer_getInt(&context->inputBuffer);
        }
        else *outIndices = ArrayBuffer_getBytes(&context->inputBuffer, drawCount * sizeofGLType(indexType));
    }

    if (ArrayBuffer_available(&context->inputBuffer) == 0) return false;
    bool meshCreated = false;

    GLClientState* clientState = &currentRenderer->clientState;

    // fixed-function client arrays go into the batch, which has to stay aligned (see GLRenderer_prepareBatchArrays)
    bool anyLegacy = false;
    for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
        if (clientState->vao->attribs[i].state == VERTEX_ATTRIB_LEGACY_ENABLED && !GLClientState_isLegacyEnabledWithProgram(clientState, i)) anyLegacy = true;
    }
    if (anyLegacy) {
        // without a vertex array nothing is drawn, and the other arrays must not shift the batch
        if (clientState->vao->attribs[POSITION_ARRAY_INDEX].state != VERTEX_ATTRIB_LEGACY_ENABLED) return true;
        GLRenderer_reserveBatchVertices(currentRenderer, drawCount);
        GLRenderer_prepareBatchArrays(currentRenderer, drawCount);
    }

    for (int i = 0, j; i < clientState->vao->maxEnabledAttribs; i++) {
        bool legacyEnabledWithProgram = GLClientState_isLegacyEnabledWithProgram(clientState, i);
        if (clientState->vao->attribs[i].state == VERTEX_ATTRIB_ENABLED || legacyEnabledWithProgram) {
            GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[i];
            int size = MIN(4, vertexAttrib->size);

            int byteCount = ArrayBuffer_getInt(&context->inputBuffer);
            void* pointer = ArrayBuffer_getBytes(&context->inputBuffer, byteCount);

            GLBuffer* oldArrayBuffer = NULL;
            if (vertexAttrib->size == GL_BGRA) {
                uint64_t offset = (uint64_t)vertexAttrib->pointer;
                swapPixelsRedBlue(pointer + offset, vertexAttrib->stride, byteCount - offset);
                oldArrayBuffer = clientState->vao->buffer[indexOfGLTarget(GL_ARRAY_BUFFER)];
                if (clientState->vao->bgraBuffer[i] == 0) glGenBuffers(1, &clientState->vao->bgraBuffer[i]);
                glBindBuffer(GL_ARRAY_BUFFER, clientState->vao->bgraBuffer[i]);
                glBufferData(GL_ARRAY_BUFFER, byteCount, pointer, GL_DYNAMIC_DRAW);
                pointer = vertexAttrib->pointer;
            }

            int location = i;
            // wined3d 生成的 shader 自带 layout(location = N)（N 即 vs_inN 的下标），此时 location 必须用 i；
            // 只有 legacy 顶点数组或纯 ARB program 路径（无 wined3d program）才需要查 gd_* 属性映射。
            // 原实现只要 arbProgram[0] 非空就覆盖 location，而 program->location.attributes[] 仅对 gd_* 内建
            // 属性填充（wined3d shader 不含 gd_*，该数组保持 calloc 的 0），会把属性绑定到错误 location。
            bool needsArbMapping = clientState->arbProgram[0] && !clientState->program;
            if (legacyEnabledWithProgram || needsArbMapping) {
                if (clientState->program) {
                    location = clientState->program->location.attributes[i];
                }
                else location = clientState->arbProgram[0]->material->location.attributes[i];
                GLRenderer_enableVertexAttribute(currentRenderer, location);
            }

            glVertexAttribPointer(location, size, vertexAttrib->type, vertexAttrib->normalized, vertexAttrib->stride, pointer);
            if (oldArrayBuffer) glBindBuffer(GL_ARRAY_BUFFER, oldArrayBuffer->id);
        }
        else if (clientState->vao->attribs[i].state == VERTEX_ATTRIB_LEGACY_ENABLED) {
            if (!meshCreated) {
                GLRenderer_beginImmediate(currentRenderer, drawMode);
                meshCreated = true;
            }

            for (j = 0; j < drawCount; j++) {
                readVertexArrayElement(context, i, -1);
                if (i == POSITION_ARRAY_INDEX) {
                    GLRenderer_addArrayElement(currentRenderer, currentRenderer->geometry.vertices.position-1);
                }
            }
        }
    }
    if (meshCreated) GLRenderer_endImmediate(currentRenderer);
    return meshCreated;
}

const char* getGLExtensions(int* outNumExtensions) {
    static int numExtensions = 0;
    static const char* extensionNames = "GL_EXT_abgr GL_ARB_shadow GL_ARB_window_pos GL_EXT_packed_pixels GL_ARB_vertex_buffer_object GL_ARB_vertex_array_object GL_ARB_texture_border_clamp GL_ARB_texture_env_add GL_EXT_texture_env_add GL_EXT_draw_range_elements GL_EXT_bgra GL_ARB_texture_compression GL_EXT_texture_compression_s3tc GL_EXT_texture_compression_dxt1 GL_EXT_texture_compression_dxt3 GL_EXT_texture_compression_dxt5 GL_ARB_point_parameters GL_EXT_point_parameters GL_EXT_texture_edge_clamp GL_EXT_multi_draw_arrays GL_ARB_multisample GL_EXT_polygon_offset GL_ARB_draw_elements_base_vertex GL_ARB_texture_rectangle GL_EXT_vertex_array GL_ARB_vertex_array_bgra GL_ARB_texture_non_power_of_two GL_EXT_blend_color GL_EXT_blend_minmax GL_EXT_blend_equation_separate GL_EXT_blend_func_separate GL_EXT_blend_subtract GL_EXT_texture_filter_anisotropic GL_ARB_texture_mirrored_repeat GL_ARB_point_sprite GL_ARB_texture_cube_map GL_EXT_texture_cube_map GL_EXT_texture_rg GL_ARB_texture_rg GL_EXT_texture_float GL_ARB_texture_float GL_EXT_texture_half_float GL_EXT_color_buffer_float GL_EXT_color_buffer_half_float GL_EXT_depth_texture GL_ARB_depth_texture GL_ARB_depth_clamp GL_ARB_ES2_compatibility GL_ARB_fragment_shader GL_ARB_vertex_shader GL_ARB_shading_language_100 GL_ARB_draw_instanced GL_EXT_draw_instanced GL_ARB_instanced_arrays GL_EXT_instanced_arrays GL_ARB_framebuffer_object GL_EXT_framebuffer_object GL_EXT_packed_depth_stencil GL_EXT_framebuffer_blit GL_ARB_draw_buffers GL_ARB_internalformat_query GL_ARB_internalformat_query2 GL_ARB_map_buffer_range GL_ARB_draw_buffers_blend GL_ARB_multitexture GL_ARB_texture_env_combine GL_EXT_texture_env_combine GL_ARB_texture_env_dot3 GL_EXT_texture_env_dot3 GL_ARB_shader_objects GL_ARB_vertex_program GL_ARB_fragment_program GL_ARB_buffer_storage GL_EXT_buffer_storage GL_ARB_sync GL_ARB_sampler_objects GL_ARB_texture_multisample GL_ARB_color_buffer_float GL_ARB_occlusion_query GL_ARB_occlusion_query2 GL_EXT_direct_state_access GL_ARB_uniform_buffer_object GL_ARB_timer_query GL_EXT_timer_query GL_ARB_texture_swizzle GL_ARB_copy_buffer GL_ARB_depth_buffer_float GL_ARB_sample_shading GL_ARB_tessellation_shader GL_ARB_derivative_control GL_ARB_compute_shader GL_EXT_gpu_program_parameters";

    if (numExtensions == 0) {
        char* ptr = (char*)extensionNames;
        while ((ptr = strchr(ptr, ' '))) {
            while (*ptr == ' ') ptr++;
            numExtensions++;
        }
    }

    if (outNumExtensions) *outNumExtensions = numExtensions;
    return extensionNames;
}