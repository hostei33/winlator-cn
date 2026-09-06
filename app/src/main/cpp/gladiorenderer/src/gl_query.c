#include "gl_context.h"

static GLuint maxQueryId = 1;
static GLQuery nullQuery = {0};

GLuint GLQuery_create() {
    GLX_CONTEXT_LOCK();
    GLuint id = maxQueryId++;
    SparseArray_put(currentRenderer->clientState.queries, id, &nullQuery);
    GLX_CONTEXT_UNLOCK();
    return id;
}

static GLQuery* findQueryWithTarget(GLenum target) {
    GLX_CONTEXT_LOCK();
    GLQuery* result = NULL;
    for (int i = 0; i < currentRenderer->clientState.queries->size; i++) {
        GLQuery* query = currentRenderer->clientState.queries->entries[i].value;
        if (query != &nullQuery && query->active && query->target == target) {
            result = query;
            break;
        }
    }
    GLX_CONTEXT_UNLOCK();
    return result;
}

static GLQuery* findQueryWithId(GLuint id) {
    GLX_CONTEXT_LOCK();
    GLQuery* query = SparseArray_get(currentRenderer->clientState.queries, id);
    if (query == &nullQuery) {
        query = calloc(1, sizeof(GLQuery));
        glGenQueries(1, &query->id);
        query->ownerId = currentRenderer->contextId;
        SparseArray_put(currentRenderer->clientState.queries, id, query);
    }
    GLX_CONTEXT_UNLOCK();
    return query;
}

void GLQuery_begin(GLenum target, GLuint id) {
    GLQuery* query = findQueryWithId(id);
    if (!query) return;
    query->target = target;
    query->active = true;
    query->counter = 0;
    currentRenderer->activeQuery = query;

    switch(target) {
        case GL_SAMPLES_PASSED:
            target = GL_ANY_SAMPLES_PASSED;
        case GL_ANY_SAMPLES_PASSED:
        case GL_ANY_SAMPLES_PASSED_CONSERVATIVE:
        case GL_PRIMITIVES_GENERATED:
        case GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN:
            glBeginQuery(target, query->id);
            break;
        case GL_TIME_ELAPSED:
            query->counter = nanoTime() - currentRenderer->queriesStartTime;
            break;
    }
}

void GLQuery_end(GLenum target) {
    bool useActiveQuery = currentRenderer->activeQuery && currentRenderer->activeQuery->target == target;
    GLQuery* query = useActiveQuery ? currentRenderer->activeQuery : findQueryWithTarget(target);
    if (!query) return;
    query->active = false;

    if (query->target == GL_TIME_ELAPSED) {
        query->counter = (nanoTime() - currentRenderer->queriesStartTime) - query->counter;
    }
    else {
        query->counter = 0;
        if (target == GL_SAMPLES_PASSED) target = GL_ANY_SAMPLES_PASSED;
        glEndQuery(target);
    }
}

void GLQuery_getParamsv(GLenum target, GLenum pname, GLint* params) {
    bool useActiveQuery = currentRenderer->activeQuery && currentRenderer->activeQuery->target == target;
    GLQuery* query = useActiveQuery ? currentRenderer->activeQuery : findQueryWithTarget(target);
    if (!query) return;

    if (pname == GL_CURRENT_QUERY) {
        *params = query->counter;
    }
    else if (pname == GL_QUERY_COUNTER_BITS) {
        *params = (query->target == GL_TIME_ELAPSED) ? 32 : 0;
    }
}

void GLQuery_getObjectParamsv(GLuint id, GLenum pname, GLint* params) {
    GLQuery* query = findQueryWithId(id);
    if (!query) return;

    if (pname == GL_QUERY_RESULT_AVAILABLE) {
        *params = GL_TRUE;
    }
    else if (pname == GL_QUERY_RESULT) {
        if (query->target == GL_TIME_ELAPSED) {
            *params = query->counter;
        }
        else {
            GLuint result = GL_FALSE;
            glGetQueryObjectuiv(query->id, pname, &result);
            query->counter = result == GL_TRUE && query->target == GL_SAMPLES_PASSED ? UINT8_MAX : result;
            *params = query->counter;
        }
    }
}

void GLQuery_queryCounter(GLuint id, GLenum target) {
    GLQuery* query = findQueryWithId(id);
    if (!query || target != GL_TIMESTAMP) return;

    query->target = GL_TIME_ELAPSED;
    query->counter = nanoTime() - currentRenderer->queriesStartTime;
}

void GLQuery_onDestroy(GLClientState* clientState) {
    // queries 表里可能残留指向静态哨兵 nullQuery 的条目（游戏 glGenQueries 后
    // 未使用或未删除就退出）。free 静态存储期的对象会导致 abort，因此这里只能
    // 释放堆分配的真实对象；批量销毁时 EGL 上下文未绑定，真实 GL query 的删除
    // 调用是无效的，其回收依赖共享命名空间的进程级清理。
    for (int i = 0; i < clientState->queries->size; i++) {
        GLQuery* query = clientState->queries->entries[i].value;
        if (query != &nullQuery) free(query);
    }
}

void GLQuery_delete(GLuint id) {
    GLX_CONTEXT_LOCK();
    GLQuery* query = SparseArray_get(currentRenderer->clientState.queries, id);
    if (query == &nullQuery) {
        SparseArray_remove(currentRenderer->clientState.queries, id);
    }
    else if (query) {
        if (query == currentRenderer->activeQuery) currentRenderer->activeQuery = NULL;
        if (query->ownerId == currentRenderer->contextId) {
            if (query->id > 0) glDeleteQueries(1, &query->id);
            SparseArray_remove(currentRenderer->clientState.queries, id);
            free(query);
        }
    }
    GLX_CONTEXT_UNLOCK();
}