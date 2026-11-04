#include "shader_material.h"
#include "shader_converter.h"
#include "gl_renderer.h"

#if DEBUG_MODE
#include "debug_utils.h"
#endif

static void setupMaterial(ShaderMaterial* material, MaterialOptions* options) {
    for (int i = 0; i < VERTEX_ATTRIB_COUNT; i++) material->location.attributes[i] = -1;

    const char* attribNames[] = {"gd_Vertex", "gd_Color", "gd_Normal"};
    for (int i = 0; i < ARRAY_SIZE(attribNames); i++) {
        material->location.attributes[i] = glGetAttribLocation(material->program, attribNames[i]);
    }

    if (options->transformVertex) {
        material->location.modelViewMatrix = glGetUniformLocation(material->program, "gd_ModelViewMatrix");
        material->location.projectionMatrix = glGetUniformLocation(material->program, "gd_ProjectionMatrix");
    }
    else {
        material->location.modelViewMatrix = -1;
        material->location.projectionMatrix = -1;
    }

    if (options->numTextures >= 1) {
        char uniformName[32];
        material->location.textureMatrix = glGetUniformLocation(material->program, "gd_TextureMatrix");

        const char* texEnvStructNames[] = {"mode", "color", "combineRGBA", "rgbaScale", "sourceRGBA", "operandRGBA", "lodBias"};
        for (int i = 0; i < MAX_TEXTURES; i++) {
            material->location.texture[i] = -1;
            for (int j = 0; j < ARRAY_SIZE(texEnvStructNames); j++) material->location.texEnv[i][j] = -1;
        }

        for (int i = 0; i < MAX_TEXTURES; i++) {
            sprintf(uniformName, "gd_Texture%d", i);
            material->location.texture[i] = glGetUniformLocation(material->program, uniformName);

            for (int j = 0; j < ARRAY_SIZE(texEnvStructNames); j++) {
                sprintf(uniformName, "gd_TexEnv%d.%s", i, texEnvStructNames[j]);
                material->location.texEnv[i][j] = glGetUniformLocation(material->program, uniformName);
            }
        }

        for (int i = TEXCOORD_ARRAY_INDEX, j = 0; j < MAX_TEXTURES; j++, i++) {
            char attribName[32];
            sprintf(attribName, "gd_MultiTexCoord%d", j);
            material->location.attributes[i] = glGetAttribLocation(material->program, attribName);
        }
    }

    for (int i = GENERIC_VERTEX_ARRAY_INDEX, j = 0; j < MAX_GENERIC_VERTEX_ATTRIBS; j++, i++) {
        char attribName[32];
        sprintf(attribName, "gd_GenericAttrib%d", i);
        material->location.attributes[i] = glGetAttribLocation(material->program, attribName);
    }

    if (options->alphaTest) {
        material->location.alphaTest = glGetUniformLocation(material->program, "gd_AlphaTest");
    }

    if (options->fog) ShaderMaterial_getFogUniformLocations(material->program, material->location.fog);
    ShaderMaterial_getPointUniformLocations(material->program, material->location.point);

    if (options->lighting) {
        char uniformName[32] = {0};
        const char* lightStructNames[] = {"ambient", "diffuse", "specular", "position", "attenuation", "spotCutoffExponent", "spotDirection"};
        for (int i = 0; i < MAX_LIGHTS; i++) {
            for (int j = 0; j < ARRAY_SIZE(lightStructNames); j++) {
                sprintf(uniformName, "lights[%d].%s", i, lightStructNames[j]);
                material->location.lights[i][j] = glGetUniformLocation(material->program, uniformName);
            }
        }
        material->location.numLights = glGetUniformLocation(material->program, "numLights");
        material->location.lightModelAmbient = glGetUniformLocation(material->program, "gd_LightModelAmbient");
        material->location.colorMaterial = glGetUniformLocation(material->program, "gd_ColorMaterial");
        material->location.twoSide = glGetUniformLocation(material->program, "gd_TwoSide");

        const char* materialStructNames[] = {"ambient", "diffuse", "specular", "emission", "alpha"};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < ARRAY_SIZE(materialStructNames); j++) {
                sprintf(uniformName, "materials[%d].%s", i, materialStructNames[j]);
                material->location.materials[i][j] = glGetUniformLocation(material->program, uniformName);
            }
        }
    }
}

static const char* getVertexShaderHead() {
    return
        "#version 320 es\n"

        "#define GD_MAX_LIGHTS 0\n"
        "#define GD_USE_TEXTURE 0\n"
        "#define GD_FOG 0\n"
        "#define GD_TRANSFORM_VERTEX 0\n"

    "#if GD_TRANSFORM_VERTEX\n"
        "uniform mat4 gd_ProjectionMatrix;\n"
        "uniform mat4 gd_ModelViewMatrix;\n"
    "#endif\n"

    "#if GD_MAX_LIGHTS > 0\n"
        "struct Light {\n"
            "vec3 ambient;\n"
            "vec3 diffuse;\n"
            "vec3 specular;\n"
            "vec4 position;\n"
            "vec3 attenuation;\n"
            "vec2 spotCutoffExponent;\n"
            "vec3 spotDirection;\n"
        "};\n"

        "struct Material {\n"
            "vec3 ambient;\n"
            "vec3 diffuse;\n"
            "vec4 specular;\n"
            "vec3 emission;\n"
            "float alpha;\n"
        "};\n"

        "uniform Light lights[GD_MAX_LIGHTS];\n"
        "uniform int numLights;\n"
        "uniform vec3 gd_LightModelAmbient;\n"
        "uniform Material materials[2];\n"
        // GL_COLOR_MATERIAL per face: 1.0 = the vertex color replaces ambient/diffuse/specular/emission
        "uniform vec4 gd_ColorMaterial[2];\n"
        "uniform float gd_TwoSide;\n"
        "out vec4 gd_BackColor;\n"

        // attenuation only applies to positional lights
        "float getLightAttenuation(Light light, vec3 viewPosition) {\n"
            "if (light.position.w == 0.0) return 1.0;\n"
            "float distance = length(light.position.xyz - viewPosition);\n"
            "return 1.0 / (light.attenuation.x + light.attenuation.y * distance + light.attenuation.z * (distance * distance));\n"
        "}\n"

        // a cutoff below 180 degrees makes a spot light, also with an exponent of 0
        "float computeSpotIntensity(Light light, vec3 lightDirection) {\n"
            "if (light.position.w == 0.0 || light.spotCutoffExponent.x >= 3.14) return 1.0;\n"
            "float angleCos = dot(-light.spotDirection, lightDirection);\n"
            "float spotCutoff = cos(light.spotCutoffExponent.x);\n"
            "return angleCos >= spotCutoff ? pow(max(angleCos, 0.0), light.spotCutoffExponent.y) : 0.0;\n"
        "}\n"

        "vec3 getLightDirection(Light light, vec3 viewPosition) {\n"
            "return light.position.w != 0.0 ? normalize(light.position.xyz - viewPosition) : light.position.xyz;\n"
        "}\n"
    "#endif\n"

        SHADER_CHUNK_POINT

        "float getPointSizeAttenuation(gd_PointParameters point, vec3 viewPosition) {\n"
            "float distance = length(viewPosition);\n"
            "return inversesqrt(point.distanceConstantAttenuation + point.distanceLinearAttenuation * distance + point.distanceQuadraticAttenuation * (distance * distance));\n"
        "}\n"

        "in vec4 gd_Vertex;\n"
        "in vec4 gd_Color;\n"
        "in vec3 gd_Normal;\n"
        "in vec4 gd_MultiTexCoord;\n"

        "out vec4 gd_FrontColor;\n"

    "#if GD_USE_TEXTURE\n"
        "uniform mat4 gd_TextureMatrix;\n"
        "out vec4 gd_TexCoord;\n"
    "#endif\n"

    "#if GD_FOG\n"
        "out float gd_FogFragCoord;\n"
    "#endif\n"
    ;
}

static const char* getVertexShaderBody() {
    return
    "#if GD_MAX_LIGHTS > 0\n"
        // per-vertex lighting of one face as in GL: the result replaces the vertex color, which only
        // contributes through GL_COLOR_MATERIAL; the alpha is the one of the diffuse material color
        "vec4 computeLighting(vec3 n, int face, vec3 viewPosition) {\n"
            "vec4 cm = gd_ColorMaterial[face];\n"
            "vec3 mAmbient = mix(materials[face].ambient, gd_Color.rgb, cm.x);\n"
            "vec3 mDiffuse = mix(materials[face].diffuse, gd_Color.rgb, cm.y);\n"
            "float mAlpha = mix(materials[face].alpha, gd_Color.a, cm.y);\n"
            "vec3 mSpecular = mix(materials[face].specular.rgb, gd_Color.rgb, cm.z);\n"
            "vec3 lit = mix(materials[face].emission, gd_Color.rgb, cm.w) + gd_LightModelAmbient * mAmbient;\n"
            "vec3 viewDirection = normalize(-viewPosition);\n"
            "float shininess = materials[face].specular.w;\n"
            "for (int i = 0; i < numLights; i++) {\n"
                "vec3 lightDirection = getLightDirection(lights[i], viewPosition);\n"
                "float factor = computeSpotIntensity(lights[i], lightDirection) * getLightAttenuation(lights[i], viewPosition);\n"
                "float NdotL = max(dot(n, lightDirection), 0.0);\n"
                "vec3 contribution = lights[i].ambient * mAmbient + NdotL * lights[i].diffuse * mDiffuse;\n"
                "if (NdotL > 0.0) {\n"
                    "vec3 halfVector = normalize(lightDirection + viewDirection);\n"
                    "contribution += pow(max(dot(n, halfVector), 0.0001), shininess) * lights[i].specular * mSpecular;\n"
                "}\n"
                "lit += factor * contribution;\n"
            "}\n"
            "return vec4(clamp(lit, 0.0, 1.0), mAlpha);\n"
        "}\n"
    "#endif\n"

        "void main() {\n"
            "gd_FrontColor = gd_Color;\n"

        "#if GD_USE_TEXTURE\n"
            "gd_TexCoord = gd_TextureMatrix * gd_MultiTexCoord;\n"
        "#endif\n"

        "#if GD_TRANSFORM_VERTEX\n"
            "vec4 position = gd_ModelViewMatrix * gd_Vertex;\n"
        "#else\n"
            "vec4 position = gd_Vertex;\n"
        "#endif\n"

        // with GL_LIGHT_MODEL_TWO_SIDE the back face is lit as well, and the fragment shader picks
        // the color by the orientation of the primitive like GL does
        "#if GD_MAX_LIGHTS > 0\n"
            "vec3 mvNormal = normalize(vec3(gd_ModelViewMatrix * vec4(gd_Normal, 0.0)));\n"
            "gd_FrontColor = computeLighting(mvNormal, 0, position.xyz);\n"
            "gd_BackColor = gd_TwoSide > 0.5 ? computeLighting(-mvNormal, 1, position.xyz) : gd_FrontColor;\n"
        "#endif\n"

        "#if GD_FOG\n"
            "gd_FogFragCoord = -position.z;\n"
        "#endif\n"

        "#if GD_TRANSFORM_VERTEX\n"
            "gl_Position = gd_ProjectionMatrix * position;\n"
        "#else\n"
            "gl_Position = position;\n"
        "#endif\n"

            "if (gd_Point.size > 0.0) gl_PointSize = clamp(gd_Point.size * getPointSizeAttenuation(gd_Point, position.xyz), gd_Point.sizeMin, gd_Point.sizeMax);\n"
        "}\n"
    ;
}

static const char* getFragmentShaderHead() {
    return
        "#version 320 es\n"
        "precision highp float;\n"
        "precision highp int;\n"

        "#define GD_MAX_LIGHTS 0\n"
        "#define GD_USE_TEXTURE 0\n"
        "#define GD_ALPHA_TEST 0\n"
        "#define GD_FOG 0\n"
        "#define GD_POINT_SPRITE 0\n"

    SHADER_CHUNK_ALPHA_TEST

        "in vec4 gd_FrontColor;\n"
    "#if GD_MAX_LIGHTS > 0\n"
        "in vec4 gd_BackColor;\n"
    "#endif\n"

    "#if GD_USE_TEXTURE\n"
        "struct gd_TexEnvParameters {\n"
            "int mode;\n"
            "vec4 color;\n"
            "ivec2 combineRGBA;\n"
            "vec2 rgbaScale;\n"
            "ivec4 sourceRGBA;\n"
            "ivec4 operandRGBA;\n"
            "float lodBias;\n"
        "};\n"

        "uniform sampler2D gd_Texture;\n"
        "uniform gd_TexEnvParameters gd_TexEnv;\n"
        "in vec4 gd_TexCoord;\n"

        "vec4 getTexEnvCombineSource(ivec2 mode, vec4 textureColor, vec4 fragColor, vec4 texEnvColor) {\n"
            "vec4 result = vec4(1.0);\n"
            "if (mode.x == " TEXENV_COMBINE_PREVIOUS ") result.rgb = fragColor.rgb;\n"
            "else if (mode.x == " TEXENV_COMBINE_TEXTURE ") result.rgb = textureColor.rgb;\n"
            "else if (mode.x == " TEXENV_COMBINE_CONSTANT ") result.rgb = texEnvColor.rgb;\n"
            "else if (mode.x == " TEXENV_COMBINE_PRIMARY_COLOR ") result.rgb = gd_FrontColor.rgb;\n"
            "if (mode.y == " TEXENV_COMBINE_PREVIOUS ") result.a = fragColor.a;\n"
            "else if (mode.y == " TEXENV_COMBINE_TEXTURE ") result.a = textureColor.a;\n"
            "else if (mode.y == " TEXENV_COMBINE_CONSTANT ") result.a = texEnvColor.a;\n"
            "else if (mode.y == " TEXENV_COMBINE_PRIMARY_COLOR ") result.a = gd_FrontColor.a;\n"
            "return result;\n"
        "}\n"

        "vec4 getTexEnvCombineOperand(ivec2 mode, vec4 source) {\n"
            "vec4 result = vec4(1.0);\n"
            "if (mode.x == " TEXENV_COMBINE_SRC_COLOR ") result.rgb = source.rgb;\n"
            "else if (mode.x == " TEXENV_COMBINE_ONE_MINUS_SRC_COLOR ") result.rgb = 1.0 - source.rgb;\n"
            "if (mode.y == " TEXENV_COMBINE_SRC_ALPHA ") result.a = source.a;\n"
            "else if (mode.y == " TEXENV_COMBINE_ONE_MINUS_SRC_ALPHA ") result.a = 1.0 - source.a;\n"
            "return result;\n"
        "}\n"

        "vec4 applyTexEnv(sampler2D sampler, vec4 texCoord, gd_TexEnvParameters texEnv, vec4 fragColor) {\n"
            "vec4 textureColor = texture(sampler, texCoord.xy / texCoord.w, texEnv.lodBias);\n"
            "int modeRGB = texEnv.mode;\n"
            "int modeAlpha = " TEXENV_MODE_MODULATE ";\n"
            "vec4 operand0 = textureColor;\n"
            "vec4 operand1 = fragColor;\n"

            "if (texEnv.mode == " TEXENV_MODE_COMBINE ") {\n"
                "modeRGB = texEnv.combineRGBA.x;\n"
                "modeAlpha = texEnv.combineRGBA.y;\n"
                "vec4 source0 = getTexEnvCombineSource(texEnv.sourceRGBA.xz, textureColor, fragColor, texEnv.color);\n"
                "vec4 source1 = getTexEnvCombineSource(texEnv.sourceRGBA.yw, textureColor, fragColor, texEnv.color);\n"
                "operand0 = getTexEnvCombineOperand(texEnv.operandRGBA.xz, source0);\n"
                "operand1 = getTexEnvCombineOperand(texEnv.operandRGBA.yw, source1);\n"
            "}\n"

            "if (modeRGB == " TEXENV_MODE_MODULATE ")\n"
                "operand0.rgb *= operand1.rgb;\n"
            "else if (modeRGB == " TEXENV_MODE_DECAL ")\n"
                "operand0.rgb = operand1.rgb * (1.0 - operand0.a) + operand0.rgb * operand0.a;\n"
            "else if (modeRGB == " TEXENV_MODE_INTERPOLATE ")\n"
                "operand0.rgb = operand1.rgb * (1.0 - operand0.rgb) + texEnv.color.rgb * operand0.rgb;\n"
            "else if (modeRGB == " TEXENV_MODE_ADD ")\n"
                "operand0.rgb += operand1.rgb;\n"
            "else if (modeRGB == " TEXENV_MODE_ADD_SIGNED ")\n"
                "operand0.rgb = operand0.rgb + operand1.rgb - 0.5;\n"
            "else if (modeRGB == " TEXENV_MODE_SUBTRACT ")\n"
                "operand0.rgb -= operand1.rgb;\n"
            "else if (modeRGB == " TEXENV_MODE_DOT3_RGB ") {\n"
                "operand0.rgb = vec3(4.0*((operand0.r - 0.5)*(operand1.r - 0.5)+(operand0.g - 0.5)*(operand1.g - 0.5)+(operand0.b - 0.5)*(operand1.b - 0.5)));\n"
                "modeAlpha = " TEXENV_MODE_REPLACE ";\n"
            "}\n"
            "else if (modeRGB == " TEXENV_MODE_DOT3_RGBA ") {\n"
                "operand0.rgba = vec4(4.0*((operand0.r - 0.5)*(operand1.r - 0.5)+(operand0.g - 0.5)*(operand1.g - 0.5)+(operand0.b - 0.5)*(operand1.b - 0.5)));\n"
                "modeAlpha = " TEXENV_MODE_REPLACE ";\n"
            "}\n"

            "if (modeAlpha == " TEXENV_MODE_MODULATE ")\n"
                "operand0.a *= operand1.a;\n"
            "else if (modeAlpha == " TEXENV_MODE_DECAL ")\n"
                "operand0.a = operand1.a * (1.0 - operand0.a) + operand0.a * operand0.a;\n"
            "else if (modeAlpha == " TEXENV_MODE_INTERPOLATE ")\n"
                "operand0.a = operand1.a * (1.0 - operand0.a) + texEnv.color.a * operand0.a;\n"
            "else if (modeAlpha == " TEXENV_MODE_ADD ")\n"
                "operand0.a += operand1.a;\n"
            "else if (modeAlpha == " TEXENV_MODE_ADD_SIGNED ")\n"
                "operand0.a = operand0.a + operand1.a - 0.5;\n"
            "else if (modeAlpha == " TEXENV_MODE_SUBTRACT ")\n"
                "operand0.a -= operand1.a;\n"

            "operand0.rgb *= texEnv.rgbaScale.x;\n"
            "operand0.a *= texEnv.rgbaScale.y;\n"
            "return operand0;\n"
        "}\n"
    "#endif\n"

    "#if GD_FOG\n"
        SHADER_CHUNK_FOG

        "in float gd_FogFragCoord;\n"

        "vec3 applyFog(gd_FogParameters fog, vec3 fragColor, float fogFragCoord) {\n"
            "float fogFactor = 0.0;\n"
            "int fogMode = int(fog.scale);\n"

            "if (fogMode == " FOG_MODE_LINEAR ") {\n"
                "fogFactor = smoothstep(fog.start, fog.end, fogFragCoord);\n"
            "}\n"
            "else if (fogMode == " FOG_MODE_EXP ") {\n"
                "fogFactor = 1.0 - clamp(exp(-fog.density * fogFragCoord), 0.0, 1.0);\n"
            "}\n"
            "else if (fogMode == " FOG_MODE_EXP2 ") {\n"
                "const float LOG2 = 1.442695;\n"
                "fogFactor = 1.0 - clamp(exp2(-fog.density * fog.density * fogFragCoord * fogFragCoord * LOG2), 0.0, 1.0);\n"
            "}\n"

            "return mix(fragColor, fog.color.rgb, fogFactor);\n"
        "}\n"
    "#endif\n"

    "#if GD_POINT_SPRITE\n"
        "uniform sampler2D gd_PointSprite;\n"

        SHADER_CHUNK_POINT
    "#endif\n"

        "out vec4 gd_FragColor;\n"
    ;
}

static const char* getFragmentShaderBody() {
    return
        "void main() {\n"

        "#if GD_MAX_LIGHTS > 0\n"
            "vec4 finalColor = gl_FrontFacing ? gd_FrontColor : gd_BackColor;\n"
        "#else\n"
            "vec4 finalColor = gd_FrontColor;\n"
        "#endif\n"

        "#if GD_POINT_SPRITE\n"
            "if (gd_Point.size > 0.0) {\n"
                "finalColor *= texture(gd_PointSprite, gl_PointCoord);\n"
                "if (finalColor.a <= 0.1) discard;\n"
            "}\n"
        "#endif\n"

        "#if GD_USE_TEXTURE\n"
            "finalColor = applyTexEnv(gd_Texture, gd_TexCoord, gd_TexEnv, finalColor);\n"
        "#endif\n"

        "#if GD_FOG\n"
            "finalColor.rgb = applyFog(gd_Fog, finalColor.rgb, gd_FogFragCoord);\n"
        "#endif\n"

        "#if GD_ALPHA_TEST\n"
            "applyAlphaTest(finalColor.a);\n"
        "#endif\n"

            "gd_FragColor = finalColor;\n"
        "}\n"
    ;
}

static char* defineShaderOptions(GLenum type, char* source, MaterialOptions* options) {
    int maxLights = options->lighting ? MAX_LIGHTS : 0;

    bool useTexture = options->numTextures >= 1;
    if (useTexture) {
        if (type == GL_VERTEX_SHADER) {
            ArrayBuffer replaces[3] = {0};
            for (int i = 0; i < options->numTextures; i++) {
                ArrayBuffer_putString(&replaces[0], "in vec4 gd_MultiTexCoord%d;\n", i);
                ArrayBuffer_putString(&replaces[1], "out vec4 gd_TexCoord%d;\n", i);
                ArrayBuffer_putString(&replaces[2], "gd_TexCoord%d = gd_TextureMatrix * gd_MultiTexCoord%d;\n", i, i);
            }

            char* searches[] = {
                "in vec4 gd_MultiTexCoord;",
                "out vec4 gd_TexCoord;",
                "gd_TexCoord = gd_TextureMatrix * gd_MultiTexCoord;"
            };
            char* oldSource = NULL;
            for (int i = 0; i < ARRAY_SIZE(replaces); i++) {
                ArrayBuffer_put(&replaces[i], '\0');
                source = str_replace(searches[i], replaces[i].buffer, oldSource = source);
                free(oldSource);
                ArrayBuffer_free(&replaces[i]);
            }
        }
        else if (type == GL_FRAGMENT_SHADER) {
            ArrayBuffer replaces[4] = {0};
            for (int i = 0; i < options->numTextures; i++) {
                if (options->fragmentProgram) {
                    char* samplerType;
                    switch (options->fragmentProgram->samplerTypes[i]) {
                        case 3:
                            samplerType = "sampler3D";
                            break;
                        case 4:
                            samplerType = "samplerCube";
                            break;
                        default:
                            samplerType = "sampler2D";
                            break;
                    }
                    ArrayBuffer_putString(&replaces[0], "uniform %s gd_Texture%d;\n", samplerType, i);
                }
                else ArrayBuffer_putString(&replaces[0], "uniform sampler2D gd_Texture%d;\n", i);
                ArrayBuffer_putString(&replaces[1], "uniform gd_TexEnvParameters gd_TexEnv%d;\n", i);
                ArrayBuffer_putString(&replaces[2], "in vec4 gd_TexCoord%d;\n", i);

                ArrayBuffer_putString(&replaces[3], "finalColor = gd_TexEnv%d.mode != 0 ? applyTexEnv(gd_Texture%d, gd_TexCoord%d, gd_TexEnv%d, finalColor) : finalColor;\n", i, i, i, i);
            }

            char* searches[] = {
                "uniform sampler2D gd_Texture;",
                "uniform gd_TexEnvParameters gd_TexEnv;",
                "in vec4 gd_TexCoord;",
                "finalColor = applyTexEnv(gd_Texture, gd_TexCoord, gd_TexEnv, finalColor);"
            };
            char* oldSource = NULL;
            for (int i = 0; i < ARRAY_SIZE(replaces); i++) {
                ArrayBuffer_put(&replaces[i], '\0');
                source = str_replace(searches[i], replaces[i].buffer, oldSource = source);
                free(oldSource);
                ArrayBuffer_free(&replaces[i]);
            }
        }
    }

    FOREACH_LINE(source, strlen(source)+1,
         if (cstartswith("#define GD_MAX_LIGHTS", line)) {
             line[lineLen-1] = INT2CHR(maxLights);
         }
         else if (cstartswith("#define GD_USE_TEXTURE", line)) {
             line[lineLen-1] = INT2CHR(useTexture);
         }
         else if (cstartswith("#define GD_ALPHA_TEST", line)) {
             line[lineLen-1] = INT2CHR(options->alphaTest);
         }
         else if (cstartswith("#define GD_FOG", line)) {
             line[lineLen-1] = INT2CHR(options->fog);
         }
         else if (cstartswith("#define GD_POINT_SPRITE", line)) {
             line[lineLen-1] = INT2CHR(options->pointSprite);
         }
         else if (cstartswith("#define GD_TRANSFORM_VERTEX", line)) {
             line[lineLen-1] = INT2CHR(options->transformVertex);
         }
    );

    return source;
}

ShaderMaterial* ShaderMaterial_create(MaterialOptions* options) {
    ShaderMaterial* material = calloc(1, sizeof(ShaderMaterial));

    char* vertexShader = strjoin('\n', 2, getVertexShaderHead(), options->vertexProgram ? options->vertexProgram->shaderCode : getVertexShaderBody());
    char* fragmentShader = strjoin('\n', 2, getFragmentShaderHead(), strdup(options->fragmentProgram ? options->fragmentProgram->shaderCode : getFragmentShaderBody()));

    vertexShader = defineShaderOptions(GL_VERTEX_SHADER, vertexShader, options);
    fragmentShader = defineShaderOptions(GL_FRAGMENT_SHADER, fragmentShader, options);

    GLuint vertexShaderId = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShaderId, 1, (const GLchar* const*) &vertexShader, NULL);
    glCompileShader(vertexShaderId);
    GLint success;

#if IS_DEBUG_ENABLED(DEBUG_MODE_SHADER_INFO)
    if (options->vertexProgram) printASMSource(options->vertexProgram->id, options->vertexProgram->asmSource);
    printShaderLines(GL_VERTEX_SHADER, vertexShaderId, generateMaterialHash(options), vertexShader, strlen(vertexShader));
#endif

    glGetShaderiv(vertexShaderId, GL_COMPILE_STATUS, &success);
    if (!success) {
        GLchar infoLog[512];
        glGetShaderInfoLog(vertexShaderId, 512, NULL, infoLog);
        println("gladio: could not compile vertex shader\n%s\n", infoLog);
        exit(1);
    }

    GLuint fragmentShaderId = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShaderId, 1, (const GLchar* const*)&fragmentShader, NULL);
    glCompileShader(fragmentShaderId);

#if IS_DEBUG_ENABLED(DEBUG_MODE_SHADER_INFO)
    if (options->fragmentProgram) printASMSource(options->fragmentProgram->id, options->fragmentProgram->asmSource);
    printShaderLines(GL_FRAGMENT_SHADER, fragmentShaderId, generateMaterialHash(options), fragmentShader, strlen(fragmentShader));
#endif

    glGetShaderiv(fragmentShaderId, GL_COMPILE_STATUS, &success);
    if (!success) {
        GLchar infoLog[512];
        glGetShaderInfoLog(fragmentShaderId, 512, NULL, infoLog);
        println("gladio: could not compile fragment shader\n%s\n", infoLog);
        exit(1);
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShaderId);
    glAttachShader(program, fragmentShaderId);
    glLinkProgram(program);

    glGetProgramiv(program, GL_LINK_STATUS, &success);
    if (!success) {
        GLchar infoLog[512];
        glGetProgramInfoLog(program, 512, NULL, infoLog);
        println("gladio: could not link program %d\n%s\n", program, infoLog);
        exit(1);
    }

    glDeleteShader(vertexShaderId);
    glDeleteShader(fragmentShaderId);

    free(vertexShader);
    free(fragmentShader);

    material->program = program;
    setupMaterial(material, options);

    return material;
}

void ShaderMaterial_destroy(ShaderMaterial* material) {
    if (!material) return;
    glDeleteProgram(material->program);
}

static void setLightUniforms(GLRenderer* renderer, ShaderMaterial* material) {
    /* 先把启用中的灯按紧凑顺序打包进暂存区，再与缓存整体比较。不能逐盏比较：灯的
       启用/禁用会改变紧凑索引 j，"第 j 盏换成了另一盏"逐盏比较是发现不了的。
       每盏 21 个 float = ambient3 + diffuse3 + specular3 + position4 + attenuation3 +
       spotCutoffExponent2 + spotDirection3，与下面 glUniform* 的分量数一一对应。 */
    float staged[MAX_LIGHTS][21];
    int numLights = 0;
    for (int i = 0; i < MAX_LIGHTS; i++) {
        GLLight* light = &renderer->lights[i];
        if (!light->enabled) continue;
        float* dst = staged[numLights++];
        memcpy(dst + 0, light->ambient, 3 * sizeof(float));
        memcpy(dst + 3, light->diffuse, 3 * sizeof(float));
        memcpy(dst + 6, light->specular, 3 * sizeof(float));
        memcpy(dst + 9, light->position, 4 * sizeof(float));
        memcpy(dst + 13, light->attenuation, 3 * sizeof(float));
        memcpy(dst + 16, light->spotCutoffExponent, 2 * sizeof(float));
        memcpy(dst + 18, light->spotDirection, 3 * sizeof(float));
    }

    /* 上游新增的三组光照状态（场景环境光、COLOR_MATERIAL 逐面掩码、双面光照开关）
       同样走值缓存：它们在一次 immediate batch 内几乎不变，逐 batch 下发是纯开销。 */
    float lightModelAmbient[3];
    memcpy(lightModelAmbient, renderer->state.lightModelAmbient, sizeof(lightModelAmbient));
    float colorMaterial[8] = {0};
    if (renderer->state.colorMaterial.enabled) {
        GLenum face = renderer->state.colorMaterial.face;
        GLenum mode = renderer->state.colorMaterial.mode;
        for (int i = 0; i < 2; i++) {
            if ((i == 0 && face == GL_BACK) || (i == 1 && face == GL_FRONT)) continue;
            colorMaterial[i * 4 + 0] = mode == GL_AMBIENT || mode == GL_AMBIENT_AND_DIFFUSE ? 1.0f : 0.0f;
            colorMaterial[i * 4 + 1] = mode == GL_DIFFUSE || mode == GL_AMBIENT_AND_DIFFUSE ? 1.0f : 0.0f;
            colorMaterial[i * 4 + 2] = mode == GL_SPECULAR ? 1.0f : 0.0f;
            colorMaterial[i * 4 + 3] = mode == GL_EMISSION ? 1.0f : 0.0f;
        }
    }
    float twoSide = renderer->state.lightModelTwoSide ? 1.0f : 0.0f;

    if (material->cache.valid && material->cache.numLights == numLights &&
        memcmp(material->cache.lights, staged, numLights * sizeof(staged[0])) == 0 &&
        memcmp(material->cache.lightModelAmbient, lightModelAmbient, sizeof(lightModelAmbient)) == 0 &&
        memcmp(material->cache.colorMaterial, colorMaterial, sizeof(colorMaterial)) == 0 &&
        material->cache.twoSide == twoSide) {
        return;
    }

    for (int j = 0; j < numLights; j++) {
        const float* src = staged[j];
        glUniform3fv(material->location.lights[j][0], 1, src + 0);
        glUniform3fv(material->location.lights[j][1], 1, src + 3);
        glUniform3fv(material->location.lights[j][2], 1, src + 6);
        glUniform4fv(material->location.lights[j][3], 1, src + 9);
        glUniform3fv(material->location.lights[j][4], 1, src + 13);
        glUniform2fv(material->location.lights[j][5], 1, src + 16);
        glUniform3fv(material->location.lights[j][6], 1, src + 18);
    }
    glUniform1i(material->location.numLights, numLights);
    glUniform3fv(material->location.lightModelAmbient, 1, lightModelAmbient);
    // GL_COLOR_MATERIAL: which material colors of each face follow the vertex color
    glUniform4fv(material->location.colorMaterial, 2, colorMaterial);
    glUniform1f(material->location.twoSide, twoSide);

    material->cache.numLights = numLights;
    memcpy(material->cache.lights, staged, numLights * sizeof(staged[0]));
    memcpy(material->cache.lightModelAmbient, lightModelAmbient, sizeof(lightModelAmbient));
    memcpy(material->cache.colorMaterial, colorMaterial, sizeof(colorMaterial));
    material->cache.twoSide = twoSide;
}

static void setMaterialUniforms(GLRenderer* renderer, ShaderMaterial* material) {
    if (!renderer->materials) return;
    GLMaterial* materials = renderer->materials;

    /* 按实际上传的分量数打包：ambient3 + diffuse3 + specular4 + emission3 + alpha1 = 14。
       alpha 是材质 diffuse 的 A 分量，光照启用时它就是受光颜色的 alpha。 */
    float staged[2][14];
    for (int i = 0; i < 2; i++) {
        memcpy(staged[i] + 0, materials[i].ambient, 3 * sizeof(float));
        memcpy(staged[i] + 3, materials[i].diffuse, 3 * sizeof(float));
        memcpy(staged[i] + 6, materials[i].specular, 4 * sizeof(float));
        memcpy(staged[i] + 10, materials[i].emission, 3 * sizeof(float));
        staged[i][13] = materials[i].alpha;
    }

    if (material->cache.valid && memcmp(material->cache.materials, staged, sizeof(staged)) == 0) return;

    for (int i = 0; i < 2; i++) {
        glUniform3fv(material->location.materials[i][0], 1, staged[i] + 0);
        glUniform3fv(material->location.materials[i][1], 1, staged[i] + 3);
        glUniform4fv(material->location.materials[i][2], 1, staged[i] + 6);
        glUniform3fv(material->location.materials[i][3], 1, staged[i] + 10);
        glUniform1f(material->location.materials[i][4], staged[i][13]);
    }

    memcpy(material->cache.materials, staged, sizeof(staged));
}

static void setARBProgramUniforms(ShaderMaterial* material, ARBProgram* program) {
    const uint8_t typeIdx = indexOfGLTarget(program->type);

    for (int i = 0, j; i < program->variables.size; i++) {
        ARBVariable* variable = program->variables.elements[i];
        if (variable->arraySize > 0) {
            for (j = 0; j < variable->arraySize; j++) {
                ARBUniform* uniform = variable->uniforms.elements[j];
                if (uniform->location == -1) {
                    char uniformName[64];
                    sprintf(uniformName, "%s%u[%d]", variable->name, typeIdx, j);
                    uniform->location = glGetUniformLocation(material->program, uniformName);
                }
                glUniform4fv(uniform->location, 1, uniform->value);
            }
        }
        else {
            ARBUniform* uniform = variable->uniforms.elements[0];
            if (uniform->location == -1) {
                char uniformName[64];
                sprintf(uniformName, "%s%u", variable->name, typeIdx);
                uniform->location = glGetUniformLocation(material->program, uniformName);
            }
            glUniform4fv(uniform->location, 1, uniform->value);
        }
    }
}

void ShaderMaterial_updatePointUniforms(ShaderMaterial* material, GLRenderer* renderer) {
    glUniform1f(material->location.point[0], renderer->state.point.size);
    glUniform1f(material->location.point[1], renderer->state.point.sizeMin);
    glUniform1f(material->location.point[2], renderer->state.point.sizeMax);
    glUniform1f(material->location.point[4], renderer->state.point.distanceAttenuation[0]);
    glUniform1f(material->location.point[5], renderer->state.point.distanceAttenuation[1]);
    glUniform1f(material->location.point[6], renderer->state.point.distanceAttenuation[2]);
}

void ShaderMaterial_updateUniforms(ShaderMaterial* material, GLRenderer* renderer, MaterialOptions* options) {
    /* 每组 uniform 都先把值打包进栈上暂存数组，再与 cache 中"上次真正下发给本 program 的
       值"做 memcmp，相同则整组跳过。本函数每个 immediate batch 都会被调用，而绝大多数
       batch 之间这些值根本没变。 */
    if (options->transformVertex) {
        const float* modelView = GLRenderer_getMatrixFromStack(renderer, MODEL_VIEW_MATRIX_INDEX);
        if (!material->cache.valid || memcmp(material->cache.modelViewMatrix, modelView, sizeof(material->cache.modelViewMatrix)) != 0) {
            glUniformMatrix4fv(material->location.modelViewMatrix, 1, GL_FALSE, modelView);
            memcpy(material->cache.modelViewMatrix, modelView, sizeof(material->cache.modelViewMatrix));
        }

        const float* projection = GLRenderer_getMatrixFromStack(renderer, PROJECTION_MATRIX_INDEX);
        if (!material->cache.valid || memcmp(material->cache.projectionMatrix, projection, sizeof(material->cache.projectionMatrix)) != 0) {
            glUniformMatrix4fv(material->location.projectionMatrix, 1, GL_FALSE, projection);
            memcpy(material->cache.projectionMatrix, projection, sizeof(material->cache.projectionMatrix));
        }
    }

    if (options->numTextures >= 1) {
        if (!options->vertexProgram) {
            const float* textureMatrix = GLRenderer_getMatrixFromStack(renderer, TEXTURE_MATRIX_INDEX);
            if (!material->cache.valid || memcmp(material->cache.textureMatrix, textureMatrix, sizeof(material->cache.textureMatrix)) != 0) {
                glUniformMatrix4fv(material->location.textureMatrix, 1, GL_FALSE, textureMatrix);
                memcpy(material->cache.textureMatrix, textureMatrix, sizeof(material->cache.textureMatrix));
            }
        }

        /* sampler uniform 的值恒等于纹理单元号，是 program 生命周期内的常量，只需设一次。
           本函数总是在 glUseProgram(material->program) 之后被调用，故此处 program 必定
           已 current，设置会落到正确的 program 上。 */
        if (!material->cache.samplersSet) {
            for (int i = 0; i < MAX_TEXTURES; i++) {
                if (material->location.texture[i] != -1) glUniform1i(material->location.texture[i], i);
            }
            material->cache.samplersSet = true;
        }

        for (int i = 0; i < MAX_TEXTURES; i++) {
            if (material->location.texture[i] == -1) continue;

            /* texEnv 的最终取值同时受三方影响：enabledTextures[i]、texEnv[i].mode，以及
               当前绑定纹理是否为 GL_ALPHA（后者会整体切换到一套硬编码的 combine/source/
               operand）。必须把三者的合成结果一起打包比较，只比较 texEnv 状态本身会在
               "换绑了一张 GL_ALPHA 纹理"时漏掉更新。 */
            GLTexture* texture = renderer->clientState.texture[i][indexOfGLTarget(GL_TEXTURE_2D)];
            bool isAlphaTexture = texture && texture->originFormat == GL_ALPHA;
            bool textureEnabled = renderer->state.enabledTextures[i] != 0;

            int stagedInts[11];
            if (isAlphaTexture) {
                stagedInts[0] = textureEnabled ? GL_COMBINE : 0;
                stagedInts[1] = GL_REPLACE;
                stagedInts[2] = renderer->state.texEnv[i].mode;
                stagedInts[3] = GL_PREVIOUS;
                stagedInts[4] = GL_PREVIOUS;
                stagedInts[5] = GL_TEXTURE;
                stagedInts[6] = GL_PREVIOUS;
                stagedInts[7] = GL_SRC_COLOR;
                stagedInts[8] = GL_SRC_COLOR;
                stagedInts[9] = GL_SRC_ALPHA;
                stagedInts[10] = GL_SRC_ALPHA;
            }
            else {
                stagedInts[0] = textureEnabled ? renderer->state.texEnv[i].mode : 0;
                memcpy(stagedInts + 1, renderer->state.texEnv[i].combineRGBA, 2 * sizeof(int));
                memcpy(stagedInts + 3, renderer->state.texEnv[i].sourceRGBA, 4 * sizeof(int));
                memcpy(stagedInts + 7, renderer->state.texEnv[i].operandRGBA, 4 * sizeof(int));
            }

            if (!material->cache.valid || memcmp(material->cache.texEnvInts[i], stagedInts, sizeof(stagedInts)) != 0) {
                glUniform1i(material->location.texEnv[i][0], stagedInts[0]);
                glUniform2iv(material->location.texEnv[i][2], 1, stagedInts + 1);
                glUniform4iv(material->location.texEnv[i][4], 1, stagedInts + 3);
                glUniform4iv(material->location.texEnv[i][5], 1, stagedInts + 7);
                memcpy(material->cache.texEnvInts[i], stagedInts, sizeof(stagedInts));
            }

            float stagedFloats[7];
            memcpy(stagedFloats + 0, renderer->state.texEnv[i].color, 4 * sizeof(float));
            memcpy(stagedFloats + 4, renderer->state.texEnv[i].rgbaScale, 2 * sizeof(float));
            stagedFloats[6] = renderer->state.texEnv[i].lodBias;

            if (!material->cache.valid || memcmp(material->cache.texEnvFloats[i], stagedFloats, sizeof(stagedFloats)) != 0) {
                glUniform4fv(material->location.texEnv[i][1], 1, stagedFloats + 0);
                glUniform2fv(material->location.texEnv[i][3], 1, stagedFloats + 4);
                glUniform1f(material->location.texEnv[i][6], stagedFloats[6]);
                memcpy(material->cache.texEnvFloats[i], stagedFloats, sizeof(stagedFloats));
            }
        }
    }

    if (options->alphaTest) {
        float staged[2];
        staged[0] = (float)(renderer->state.alphaTest.enabled ? renderer->state.alphaTest.func : GL_ALWAYS);
        staged[1] = renderer->state.alphaTest.ref;
        if (!material->cache.valid || memcmp(material->cache.alphaTest, staged, sizeof(staged)) != 0) {
            glUniform2fv(material->location.alphaTest, 1, staged);
            memcpy(material->cache.alphaTest, staged, sizeof(staged));
        }
    }

    if (options->fog) {
        if (!material->cache.valid || memcmp(material->cache.fogColor, renderer->state.fog.color, sizeof(material->cache.fogColor)) != 0) {
            glUniform4fv(material->location.fog[0], 1, renderer->state.fog.color);
            memcpy(material->cache.fogColor, renderer->state.fog.color, sizeof(material->cache.fogColor));
        }

        float staged[4];
        staged[0] = renderer->state.fog.density;
        staged[1] = renderer->state.fog.start;
        staged[2] = renderer->state.fog.end;
        staged[3] = (float)renderer->state.fog.mode;
        if (!material->cache.valid || memcmp(material->cache.fogParams, staged, sizeof(staged)) != 0) {
            glUniform1f(material->location.fog[1], staged[0]);
            glUniform1f(material->location.fog[2], staged[1]);
            glUniform1f(material->location.fog[3], staged[2]);
            glUniform1f(material->location.fog[4], staged[3]);
            memcpy(material->cache.fogParams, staged, sizeof(staged));
        }
    }

    if (options->lighting) {
        setLightUniforms(renderer, material);
        setMaterialUniforms(renderer, material);
    }

    material->cache.valid = true;

    if (options->vertexProgram) setARBProgramUniforms(material, options->vertexProgram);
    if (options->fragmentProgram) setARBProgramUniforms(material, options->fragmentProgram);
}