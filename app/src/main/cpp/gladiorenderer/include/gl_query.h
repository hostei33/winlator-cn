#ifndef GLADIO_GL_QUERY_H
#define GLADIO_GL_QUERY_H

#include "gladio.h"

typedef struct GLClientState GLClientState;

typedef struct GLQuery {
    GLuint ownerId;
    GLuint id;
    bool active;
    GLenum target;
    uint32_t counter;
} GLQuery;

extern GLuint GLQuery_create();
extern void GLQuery_begin(GLenum target, GLuint id);
extern void GLQuery_end(GLenum target);
extern void GLQuery_getParamsv(GLenum target, GLenum pname, GLint* params);
extern void GLQuery_getObjectParamsv(GLuint id, GLenum pname, GLint* params);
extern void GLQuery_queryCounter(GLuint id, GLenum target);
extern void GLQuery_delete(GLuint id);
extern void GLQuery_onDestroy(GLClientState* clientState);

#endif
