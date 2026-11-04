#version 150

#moj_import <fog.glsl>

// Zenith 龙卷网格：原版 particle.fsh 去掉 "alpha < 0.1 discard"。
// 大面积叠加的软边云团在 10% 透明度处被硬切会出现一圈亮边，这里只丢掉几乎完全透明的像素。

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
