#version 150

// Zenith 体积龙卷：代理盒的背面（CPU 只提交背向相机的面，每个像素恰好着色一次）。
// Position 是相机相对的世界坐标（未旋转），射线方向就是它本身。

in vec3 Position;

uniform mat4 ZViewMat;
uniform mat4 ZProjMat;

out vec3 vRel;

void main() {
    vRel = Position;
    gl_Position = ZProjMat * ZViewMat * vec4(Position, 1.0);
}
