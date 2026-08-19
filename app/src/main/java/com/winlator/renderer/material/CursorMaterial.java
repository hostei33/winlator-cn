package com.winlator.renderer.material;

public class CursorMaterial extends ShaderMaterial {
    public final Uniforms uniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform xform = new Uniform("xform");
        public final Uniform viewSize = new Uniform("viewSize");
        public final Uniform texture = new Uniform("texture");
        public final Uniform backColor = new Uniform("backColor");
        public final Uniform foreColor = new Uniform("foreColor");
    }

    @Override
    protected String getVertexShader() {
        return String.join("\n",
            "in vec2 position;",
            "out vec2 vUV;",
            "uniform float xform[6];",
            "uniform vec2 viewSize;",

            "void main() {",
                "vUV = position;",
                "vec2 transformedPos = applyXForm(position, xform);",
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision mediump float;",

            "uniform sampler2D cursorTexture;",
            "uniform vec3 backColor;",
            "uniform vec3 foreColor;",
            "in vec2 vUV;",

            "layout(location = 0) out vec4 outFragColor;",

            "void main() {",
                "vec4 texelColor = texture(cursorTexture, vUV);",
                "outFragColor = vec4(mix(foreColor, backColor, texelColor.r), texelColor.a);",
            "}"
        );
    }
}
