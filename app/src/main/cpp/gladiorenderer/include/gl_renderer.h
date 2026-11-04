#ifndef GLADIO_GL_RENDERER_H
#define GLADIO_GL_RENDERER_H

#include <EGL/egl.h>

#include "shader_material.h"
#include "shader_converter.h"
#include "gl_texture.h"
#include "mat4.h"
#include "vec3.h"
#include "compressed_texture.h"
#include "gl_buffer.h"
#include "gl_client_state.h"
#include "gl_query.h"

#define MODEL_VIEW_MATRIX_INDEX 0
#define PROJECTION_MATRIX_INDEX 1
#define TEXTURE_MATRIX_INDEX 2

typedef struct GLLight {
    bool enabled;
    float ambient[3];
    float diffuse[3];
    float specular[3];
    float position[4];
    float attenuation[3];
    float spotCutoffExponent[2];
    float spotDirection[3];
} GLLight;

typedef struct GLMaterial {
    float ambient[3];
    float diffuse[3];
    float specular[4];
    float emission[3];
    /* GL 规范里 glMaterialfv(GL_DIFFUSE) 的 params 是 4 个 float，第 4 个分量是材质 alpha，
       光照启用时它就是受光颜色的 alpha（ambient/specular/emission 的 alpha 不参与光照）。 */
    float alpha;
} GLMaterial;

typedef struct Mesh {
    GLenum mode;
    int start;
    int end;
    int indexCount;
} Mesh;

typedef struct Geometry {
    ArrayBuffer vertices;
    ArrayBuffer colors;
    ArrayBuffer normals;
    ArrayBuffer texCoords[MAX_TEXTURES];
    ArrayBuffer indices;
} Geometry;

typedef struct GLRaster {
    float position[4];
    float pixelZoom[2];
    GLuint textureId;

    struct {
        ArrayBuffer vertices;
        ArrayBuffer texCoords;
    } quad;
} GLRaster;

typedef struct TexEnv {
    GLenum mode;
    GLfloat color[4];
    GLint combineRGBA[2];
    GLfloat rgbaScale[2];
    GLint sourceRGBA[4];
    GLint operandRGBA[4];
    GLfloat lodBias;
} TexEnv;

typedef struct PixelReadCache {
    GLuint framebuffer;
    GLenum format;
    GLenum type;
    int dataSize;
    void* data;
    GLuint frameIndex;
    bool valid;
} PixelReadCache;

typedef struct GLState {
    bool lighting;
    GLenum polygonMode;

    struct {
        bool enabled;
        GLenum face;
        GLenum mode;
    } colorMaterial;

    struct {
        bool enabled;
        GLenum func;
        float ref;
    } alphaTest;

    struct {
        float color[4];
        float density;
        float start;
        float end;
        GLenum mode;
        bool enabled;
    } fog;

    struct {
        float size;
        float sizeMin;
        float sizeMax;
        float fadeThresholdSize;
        float distanceAttenuation[3];
        GLenum spriteCoordOrigin;
        bool sprite;
        bool smooth;
    } point;

    float color[4];
    float normal[3];
    float lightModelAmbient[4];
    bool lightModelTwoSide;
    float texCoords[MAX_TEXTURES][4];
    TexEnv texEnv[MAX_TEXTURES];
    uint8_t enabledTextures[MAX_TEXTURES];
    bool enabledARBPrograms[2];

    GLenum shadeModel;
    bool clampReadColor;
} GLState;

typedef struct GLRenderer {
    int contextId;
    short displaySize[2];
    GLuint displayBuffer;
    GLuint bufferIds[VERTEX_ATTRIB_COUNT+1];

    GLState state;
    GLClientState clientState;

    Geometry geometry;
    GLMaterial* materials;

    GLLight lights[MAX_LIGHTS];
    SparseArray materialMap;
    ArrayDeque meshes;
    Mesh* activeMesh;

    ArrayList matrixStack[3];
    GLuint matrixIndex;

    GLRaster* raster;
    ArrayList attribStacks;
    bool enabledVertexAttribs[VERTEX_ATTRIB_COUNT];
    GLuint circleTexture;

    GLQuery* activeQuery;
    uint64_t queriesStartTime;
    uint32_t frameCount;

    PixelReadCache* pixelReadCache;
} GLRenderer;

extern void GLRenderer_initOnEGLContext(GLRenderer* renderer);
/* 查询真实驱动是否支持 GL_EXT_texture_compression_s3tc。结果进程内缓存，首次调用必须在
   EGL 上下文已 current 时进行（glGetString 依赖当前上下文）。 */
extern bool GLRenderer_isS3TCPassthroughSupported();
extern bool GLRenderer_useARBProgram(GLRenderer* renderer, bool fullUpdate);
extern void GLRenderer_drawImmediate(GLRenderer* renderer);
extern void GLRenderer_beginImmediate(GLRenderer* renderer, GLenum mode);
extern void GLRenderer_endImmediate(GLRenderer* renderer);
extern float* GLRenderer_getMatrixFromStack(GLRenderer* renderer, int index);
extern float* GLRenderer_getCurrentMatrix(GLRenderer* renderer);
extern void GLRenderer_pushMatrix(GLRenderer* renderer);
extern void GLRenderer_popMatrix(GLRenderer* renderer);
extern void GLRenderer_addVertex(GLRenderer* renderer, GLfloat x, GLfloat y, GLfloat z, GLfloat w);
extern void GLRenderer_setCurrentAttrib(GLRenderer* renderer, int arrayIdx, const float* newValue);
extern void GLRenderer_prepareBatchArrays(GLRenderer* renderer, int addedVertices);
extern void GLRenderer_reserveBatchVertices(GLRenderer* renderer, int addedVertices);
extern void GLRenderer_addArrayElement(GLRenderer* renderer, int index);
extern void GLRenderer_setCapabilityState(GLRenderer* renderer, GLenum cap, bool state, int index);
/* 取「实际应作为顶点颜色下发」的 RGBA：光照关闭时即当前 raster color；
   光照启用时按规范把 A 分量替换为材质 diffuse 的 alpha。
   所有把 state.color 当顶点色喂给 GPU 的站点都必须经由此函数。 */
extern void GLRenderer_setMaterialParams(GLRenderer* renderer, GLenum face, GLenum pname, void* params);
extern void GLRenderer_setLightParams(GLRenderer* renderer, GLenum id, GLenum pname, void* params);
extern void GLRenderer_setLightModelParams(GLRenderer* renderer, GLenum pname, GLfloat* params);
extern void GLRenderer_setTexEnvParams(GLRenderer* renderer, GLenum target, GLenum pname, float* params);
extern void GLRenderer_setFogParams(GLRenderer* renderer, GLenum pname, GLfloat* params);
extern void GLRenderer_setPointParams(GLRenderer* renderer, GLenum pname, void* params);
extern void GLRenderer_destroy(GLRenderer* renderer);
extern int GLRenderer_getParamsv(GLRenderer* renderer, GLenum pname, GLenum type, void* params);
extern void GLRenderer_setPixelZoom(GLRenderer* renderer, GLfloat xfactor, GLfloat yfactor);
extern void GLRenderer_setRasterPos(GLRenderer* renderer, bool transform, GLfloat x, GLfloat y, GLfloat z, GLfloat w);
extern void GLRenderer_drawPixels(GLRenderer* renderer, GLsizei width, GLsizei height, GLenum format, GLenum type, void* pixels);
extern void GLRenderer_setSamplerParameter(GLRenderer* renderer, GLuint sampler, GLenum pname, GLfloat* params);
extern void GLRenderer_getSamplerParameter(GLRenderer* renderer, GLuint sampler, GLenum pname, GLfloat* params);
extern void GLRenderer_setTexParameter(GLRenderer* renderer, GLenum target, GLenum pname, GLfloat* params);
extern void GLRenderer_getTexParameter(GLRenderer* renderer, GLenum target, GLint level, GLenum pname, GLfloat* params);
extern void* GLRenderer_getTexImage(GLRenderer* renderer, GLenum target, GLint level, GLenum format, GLenum type, int* imageSize);
extern void* GLRenderer_getCompressedTexImage(GLRenderer* renderer, GLenum target, GLint level, int* compressedSize);
extern void GLRenderer_setDrawBuffer(GLRenderer* renderer, GLenum drawBuffer);
extern void GLRenderer_enableVertexAttribute(GLRenderer* renderer, int location);
extern void GLRenderer_disableVertexAttribute(GLRenderer* renderer, int location);
extern void GLRenderer_disableUnusedVertexAttributes(GLRenderer* renderer);
extern void GLRenderer_resetFrameCount(GLRenderer* renderer);
extern void GLRenderer_invalidatePixelReadCache(GLRenderer* renderer);
extern void GLRenderer_readPixels(GLRenderer* renderer, GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void* pixels, bool allowCache);

extern thread_local GLRenderer* currentRenderer;

#endif
