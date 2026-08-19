package com.winlator.renderer.material;

public class ScreenMaterial extends ShaderMaterial {
    public final Uniforms uniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform resolution = new Uniform("resolution");
        public final Uniform screenTexture = new Uniform("screenTexture");
        public final Uniform flipY = new Uniform("flipY");
    }

    @Override
    protected String getVertexShader() {
        return String.join("\n",
            "in vec2 position;",
            "out vec2 vUV;",
            "uniform bool flipY;",

            "void main() {",
                "vUV = vec2(position.x, flipY ? (1.0 - position.y) : position.y);",
                "gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision mediump float;",

            "uniform sampler2D screenTexture;",
            "in vec2 vUV;",

            "layout(location = 0) out vec4 outFragColor;",

            "void main() {",
                "outFragColor = texture(screenTexture, vUV);",
            "}"
        );
    }
}
