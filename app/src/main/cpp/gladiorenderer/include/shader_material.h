#ifndef GLADIO_SHADER_MATERIAL_H
#define GLADIO_SHADER_MATERIAL_H

#include "gladio.h"
#include "arb_program.h"

#define TEXENV_MODE_MODULATE "8448"
#define TEXENV_MODE_DECAL "8449"
#define TEXENV_MODE_INTERPOLATE "34165"
#define TEXENV_MODE_ADD "260"
#define TEXENV_MODE_ADD_SIGNED "34164"
#define TEXENV_MODE_SUBTRACT "34023"
#define TEXENV_MODE_COMBINE "34160"
#define TEXENV_MODE_DOT3_RGB "34478"
#define TEXENV_MODE_DOT3_RGBA "34479"
#define TEXENV_MODE_REPLACE "7681"
#define TEXENV_COMBINE_TEXTURE "5890"
#define TEXENV_COMBINE_CONSTANT "34166"
#define TEXENV_COMBINE_PRIMARY_COLOR "34167"
#define TEXENV_COMBINE_PREVIOUS "34168"
#define TEXENV_COMBINE_SRC_COLOR "768"
#define TEXENV_COMBINE_ONE_MINUS_SRC_COLOR "769"
#define TEXENV_COMBINE_SRC_ALPHA "770"
#define TEXENV_COMBINE_ONE_MINUS_SRC_ALPHA "771"

#define ALPHA_TEST_FUNC_NEVER "512"
#define ALPHA_TEST_FUNC_LESS "513"
#define ALPHA_TEST_FUNC_EQUAL "514"
#define ALPHA_TEST_FUNC_LEQUAL "515"
#define ALPHA_TEST_FUNC_GREATER "516"
#define ALPHA_TEST_FUNC_NOTEQUAL "517"
#define ALPHA_TEST_FUNC_GEQUAL "518"
#define ALPHA_TEST_FUNC_ALWAYS "519"

#define FOG_MODE_LINEAR "9729"
#define FOG_MODE_EXP "2048"
#define FOG_MODE_EXP2 "2049"

typedef struct MaterialOptions {
    bool lighting;
    bool alphaTest;
    bool fog;
    bool pointSprite;
    bool transformVertex;
    uint8_t numTextures;
    ARBProgram* vertexProgram;
    ARBProgram* fragmentProgram;
} MaterialOptions;

typedef struct ShaderMaterial {
    GLuint program;

    struct {
        int attributes[VERTEX_ATTRIB_COUNT];
        int projectionMatrix;
        int modelViewMatrix;
        int textureMatrix;
        int alphaTest;
        int useTexture;
        int texture[MAX_TEXTURES];
        int texEnv[MAX_TEXTURES][7];
        int point[7];
        int fog[5];

        int lights[MAX_LIGHTS][7];
        int numLights;
        int lightModelAmbient;
        int colorMaterial;
        int twoSide;

        int materials[2][5];
    } location;

    /* 上次真正下发给本 program 的 uniform 值。ShaderMaterial_updateUniforms 每个 immediate
       batch 都会被调用，而绝大多数 batch 之间这些值根本没变；逐组 memcmp 后可跳过大量
       glUniform* 调用（1 纹理 1 灯约 33 次，4 纹理 3 灯约 71 次，MAX_LIGHTS 提到 8 后
       最坏情况更多），这些调用全部串行发生在渲染线程上。
       之所以用"上传点值缓存"而不是"变更点脏标"：uniform 是 per-program 状态（materialMap
       里有多个 ShaderMaterial，wined3d 侧还有任意多个 ShaderProgram），且 texEnv 的取值
       同时受 enabledTextures、texEnv.mode 与当前绑定纹理的 originFormat 三方影响，脏标
       需要覆盖全部变更点，漏一处就是很难复现的画面错误。值缓存无需任何插桩。
       结构体由 calloc 零初始化，配合 valid 保证首次一定上传。 */
    struct {
        bool valid;
        bool samplersSet;
        float modelViewMatrix[16];
        float projectionMatrix[16];
        float textureMatrix[16];
        /* 每个纹理单元两组：整型 = mode + combineRGBA[2] + sourceRGBA[4] + operandRGBA[4]，
           浮点 = color[4] + rgbaScale[2] + lodBias。用扁平数组而非结构体，避免结构体填充
           字节未初始化导致 memcmp 出现虚假不等。 */
        int texEnvInts[MAX_TEXTURES][11];
        float texEnvFloats[MAX_TEXTURES][7];
        float alphaTest[2];
        float fogColor[4];
        float fogParams[4];
        int numLights;
        float lights[MAX_LIGHTS][21];
        float lightModelAmbient[3];
        float colorMaterial[8];
        float twoSide;
        float materials[2][14];
    } cache;
} ShaderMaterial;

extern ShaderMaterial* ShaderMaterial_create(MaterialOptions* options);
extern void ShaderMaterial_destroy(ShaderMaterial* material);
extern void ShaderMaterial_updatePointUniforms(ShaderMaterial* material, GLRenderer* renderer);
extern void ShaderMaterial_updateUniforms(ShaderMaterial* material, GLRenderer* renderer, MaterialOptions* options);

static inline uint32_t generateMaterialHash(MaterialOptions* options) {
    const char key[] = {0, 0, 0, 0, '-', 0, 0, 0, 0, '-', INT2CHR(options->lighting), '-', INT2CHR(options->alphaTest), '-', INT2CHR(options->fog), '-', INT2CHR(options->pointSprite), '-', INT2CHR(options->transformVertex), '-', INT2CHR(options->numTextures)};
    *(int*)(key+0) = options->vertexProgram ? options->vertexProgram->id : 0;
    *(int*)(key+5) = options->fragmentProgram ? options->fragmentProgram->id : 0;
    return fnv1aHash32(key, sizeof(key));
}

static inline void ShaderMaterial_getFogUniformLocations(GLuint program, int* locations) {
    char uniformName[64] = {0};
    const char* fogStructNames[] = {"color", "density", "start", "end", "scale"};
    for (int i = 0; i < ARRAY_SIZE(fogStructNames); i++) {
        sprintf(uniformName, "gd_Fog.%s", fogStructNames[i]);
        locations[i] = glGetUniformLocation(program, uniformName);
    }
}

static inline void ShaderMaterial_getPointUniformLocations(GLuint program, int* locations) {
    char uniformName[64] = {0};
    const char* pointStructNames[] = {"size", "sizeMin", "sizeMax", "fadeThresholdSize", "distanceConstantAttenuation", "distanceLinearAttenuation", "distanceQuadraticAttenuation"};
    for (int i = 0; i < ARRAY_SIZE(pointStructNames); i++) {
        sprintf(uniformName, "gd_Point.%s", pointStructNames[i]);
        locations[i] = glGetUniformLocation(program, uniformName);
    }
}

#endif