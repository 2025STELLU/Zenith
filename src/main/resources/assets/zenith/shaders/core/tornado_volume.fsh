#version 150

// =====================================================================================
// Zenith 体积龙卷（可选画质"体积云"）
//
// 密度场由三部分组成，外形公式与 Java 侧 TornadoShape / TornadoFrame 完全一致：
//   1. 凝结漏斗：兰金涡等压面，r(z) 由 A·g(n) = 0.85·(1−z)^1.5 反解
//   2. 母云云底 + 墙云：云底下沉成碗状，边缘淡出
//   3. 地面碎屑云：近地强风卷起的尘土
// 光照：沿太阳方向 3 次采样估计自遮挡，Henyey–Greenstein 相函数（逆光银边），
//       环境光随昼夜变暗；闪电时云底整体提亮。
// 步进：按"离漏斗包络 / 云底的距离"自适应步长（远处大步、贴近细步），首步抖动消除条带。
// =====================================================================================

uniform sampler2D Sampler0;     // 场景深度（拷贝）

uniform mat4 ZViewMat;
uniform mat4 ZProjMat;
uniform vec2 ZScreen;
uniform vec3 ZCenter;           // 地面接触点，相机相对
uniform vec4 ZShape;            // coreR, strength, height, spin
uniform vec4 ZAnim;             // time, rope, debris, flash
uniform vec4 ZAxis;             // tiltX, tiltZ, wobble, seed
uniform vec4 ZDeck;             // topR, baseR, lowering, vis
uniform vec4 ZSun;              // xyz = 主光方向，w = daylight
uniform vec4 ZDebris;           // rgb = 碎屑颜色，a = 环境亮度（光照贴图天空亮度）
uniform vec4 ZFog;              // start, end, shape(0 球 / 1 柱), 0
uniform vec4 ZFogColor;
uniform vec4 ZQuality;          // maxSteps, lightSamples(0/1), frame, 0

in vec3 vRel;
out vec4 fragColor;

const float PI = 3.14159265;

// ---------------------------------------------------------------- 外形（与 Java 一致）

float thresholdAt(float z) {
    return 0.85 * pow(1.0 - clamp(z, 0.0, 1.0), 1.5);
}

float radiusFactor(float z, float A) {
    if (A <= 1e-4) return 0.0;
    float thr = thresholdAt(z);
    if (thr >= A) return 0.0;
    float q = thr / A;
    float n = q >= 0.5 ? sqrt(2.0 * (1.0 - q)) : sqrt(1.0 / (2.0 * max(q, 1e-4)));
    return min(n, 4.2);
}

vec2 axisOffset(float z) {
    float H = ZShape.z, t = ZAnim.x, seed = ZAxis.w;
    float lean = H * pow(max(z, 0.0), 1.6);
    float env = sin(PI * min(1.0, max(z, 0.0) * 1.15));
    float wx = sin(2.0 * PI * (1.3 * z - 0.15 * t) + seed);
    float wz = cos(2.0 * PI * (0.9 * z - 0.11 * t) + seed * 1.7);
    float wx2 = sin(2.0 * PI * (2.7 * z - 0.31 * t) + seed * 2.3);
    return ZAxis.xy * lean + ZAxis.z * env * vec2(wx + 0.35 * wx2, wz + 0.35 * wx2);
}

float funnelBottom(float A) {
    if (A >= 0.85) return 0.0;
    if (A <= 1e-4) return 1.0;
    return 1.0 - pow(A / 0.85, 1.0 / 1.5);
}

// ---------------------------------------------------------------- 噪声

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i + vec3(0, 0, 0)), hash3(i + vec3(1, 0, 0)), f.x),
                   mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x),
                   mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm3(vec3 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 3; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + vec3(17.1, 3.7, 9.2);
        a *= 0.5;
    }
    return s / 0.875;
}

vec2 rot2(vec2 v, float a) {
    float c = cos(a), s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// ---------------------------------------------------------------- 密度场
// 返回 x = 总密度，y = 碎屑占比，z = 云底占比

vec3 density(vec3 p, out float envelopeDist) {
    float coreR = ZShape.x, A = ZShape.y, H = ZShape.z, spin = ZShape.w;
    float t = ZAnim.x, debris = ZAnim.z;
    float topR = ZDeck.x, baseR = ZDeck.y, lowering = ZDeck.z;
    float zeta = p.y / H;
    float zb = funnelBottom(A);

    float dFun = 0.0, dDeck = 0.0, dDeb = 0.0;
    envelopeDist = 1e5;

    // ---- 漏斗
    if (zeta > zb - 0.03 && zeta < 1.06) {
        float zc = clamp(zeta, 0.0, 1.0);
        vec2 q = p.xz - axisOffset(zc);
        float rho = length(q);
        float R = coreR * radiusFactor(zc, A);
        if (zb <= 0.0) R = max(R, coreR * 0.35);
        envelopeDist = rho - R * 1.5 - 2.0;
        if (R > 0.05 && rho < R * 1.6 + 2.0) {
            float rate = spin * (0.22 + 0.38 * min(1.2, A)) / max(0.6, sqrt(max(radiusFactor(0.5, A), 0.01)));
            vec2 qr = rot2(q, rate * 2.0 * PI * t);
            float up = (0.25 + 0.45 * min(1.0, A)) * 8.0 * t;
            vec3 np = vec3(qr.x / 5.5, (p.y - up) / 13.0 + zc * 2.0 * spin, qr.y / 5.5);
            float n = fbm3(np);
            float shell = R * (0.95 + 0.45 * (n - 0.5));
            float d = smoothstep(shell, shell * 0.5, rho);
            d *= smoothstep(zb, zb + (zb <= 0.0 ? 0.03 : 0.10), zeta);
            d *= 1.0 - smoothstep(0.78, 1.04, zeta) * 0.75;   // 喇叭口交给云底，免得顶出一圈亮"领子"
            dFun = d * (1.2 + 0.6 * n);
        }
    }

    // ---- 云底与墙云
    {
        vec2 q = p.xz - axisOffset(1.0);
        float rho = length(q);
        float k = rho / max(1.0, topR * 2.2);
        float lower = lowering * exp(-k * k);
        float hRel = p.y - (H - lower);
        float deckDist = max(abs(hRel - 7.0) - 12.0, rho - baseR);
        envelopeDist = min(envelopeDist, deckDist);
        if (hRel > -16.0 && hRel < 22.0 && rho < baseR) {
            vec2 qr = rot2(q, spin * 0.018 * 2.0 * PI * t);
            float n = fbm3(vec3(qr.x / 26.0, p.y / 10.0 + t * 0.02, qr.y / 26.0));
            float n2 = vnoise(vec3(qr.x / 7.0, p.y / 5.0 - t * 0.05, qr.y / 7.0));
            // 云底起伏：大尺度团块 + 小尺度碎絮
            float baseLine = (n - 0.5) * 10.0 + (n2 - 0.5) * 3.5;
            float d = smoothstep(baseLine - 1.5, baseLine + 3.5, hRel) * (1.0 - smoothstep(12.0, 21.0, hRel));
            // 外缘不是正圆：用噪声扰动淡出半径，避免"飞碟"一样的整齐边
            float edgeN = vnoise(vec3(qr.x / 40.0, 3.7, qr.y / 40.0));
            d *= 1.0 - smoothstep(baseR * (0.35 + 0.3 * edgeN), baseR * (0.8 + 0.2 * edgeN), rho);
            dDeck = d * (0.15 + 0.95 * n);
        }
    }

    // ---- 地面碎屑
    if (debris > 0.01 && p.y < 26.0) {
        float rho = length(p.xz);
        float hD = (5.0 + 14.0 * debris) * (0.7 + 0.3 * min(1.0, A));
        float rOut = coreR * (2.4 + 0.8 * debris);
        envelopeDist = min(envelopeDist, max(rho - rOut * 1.5, p.y - hD * 1.6));
        if (rho < rOut * 1.5 && p.y < hD * 1.6) {
            vec2 qr = rot2(p.xz, ZShape.w * 0.5 * 2.0 * PI * t);
            float n = fbm3(vec3(qr.x / 6.0, (p.y - t * 3.0) / 5.0, qr.y / 6.0));
            float rn = rho / rOut;
            // 高斯状的软团：径向、竖向都平滑衰减，中心略空（龙卷眼附近风小、尘少）
            // 减去尾巴，保证在包围范围边上密度正好归零（否则会切出一圈硬边）
            float d = max(0.0, exp(-rn * rn * 2.8) - 0.0019) * max(0.0, exp(-max(p.y, 0.0) / (hD * 0.45)) - 0.029);
            d *= 0.45 + 0.55 * smoothstep(0.0, coreR * 0.6, rho);
            d *= smoothstep(0.42, 0.85, n + 0.05);
            dDeb = d * debris * 0.8;
        }
    }

    float total = dFun + dDeck + dDeb;
    if (total <= 1e-4) return vec3(0.0);
    return vec3(total, dDeb / total, dDeck / total);
}

float densityOnly(vec3 p) {
    float e;
    return density(p, e).x;
}

float hg(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

// 射线与竖直圆柱（轴 xz = c，半径 r，y ∈ [y0, y1]）求交
vec2 cylinder(vec3 ro, vec3 rd, vec2 c, float r, float y0, float y1) {
    vec2 o = ro.xz - c;
    float a = dot(rd.xz, rd.xz);
    float b = dot(o, rd.xz);
    float cc = dot(o, o) - r * r;
    float tn = -1e9, tf = 1e9;
    if (a > 1e-8) {
        float disc = b * b - a * cc;
        if (disc < 0.0) return vec2(1.0, -1.0);
        float s = sqrt(disc);
        tn = (-b - s) / a;
        tf = (-b + s) / a;
    } else if (cc > 0.0) {
        return vec2(1.0, -1.0);
    }
    if (abs(rd.y) > 1e-6) {
        float ta = (y0 - ro.y) / rd.y, tb = (y1 - ro.y) / rd.y;
        tn = max(tn, min(ta, tb));
        tf = min(tf, max(ta, tb));
    } else if (ro.y < y0 || ro.y > y1) {
        return vec2(1.0, -1.0);
    }
    return vec2(tn, tf);
}

void main() {
    vec3 rd = normalize(vRel);
    vec3 ro = -ZCenter;  // 在"龙卷局部坐标"里（原点 = 地面接触点）的相机位置

    float H = ZShape.z;
    float baseR = ZDeck.y;
    vec2 tr = cylinder(ro, rd, vec2(0.0), baseR + abs(ZAxis.x) * H + abs(ZAxis.z) * 2.0 + 4.0, -3.0, H + 24.0);
    float t0 = max(tr.x, 0.05);
    float t1 = tr.y;

    // 场景深度：线性化后换算成沿射线的距离
    vec2 uv = gl_FragCoord.xy / ZScreen;
    float depth = texture(Sampler0, uv).r;
    if (depth < 1.0) {
        float ndc = depth * 2.0 - 1.0;
        float zv = -ZProjMat[3][2] / (ndc + ZProjMat[2][2]);
        vec3 fwd = transpose(mat3(ZViewMat)) * vec3(0.0, 0.0, -1.0);
        float tScene = (-zv) / max(1e-4, dot(rd, fwd));
        t1 = min(t1, tScene);
    }
    if (t1 <= t0) discard;

    vec3 sun = normalize(ZSun.xyz);
    float daylight = ZSun.w;
    float ambient = ZDebris.a;
    float cosT = dot(rd, sun);
    float phase = mix(hg(cosT, 0.55), hg(cosT, -0.2), 0.35) * 4.0 * PI;

    vec3 funCol = vec3(0.47, 0.49, 0.53);
    vec3 deckCol = vec3(0.30, 0.33, 0.36);
    vec3 debCol = ZDebris.rgb;
    float flash = ZAnim.w;

    float jitter = hash3(vec3(gl_FragCoord.xy, ZQuality.z));
    int maxSteps = int(ZQuality.x);
    float t = t0 + jitter * 1.5;
    float T = 1.0;
    vec3 C = vec3(0.0);
    float firstHit = -1.0;

    for (int i = 0; i < 160; i++) {
        if (i >= maxSteps || t > t1 || T < 0.02) break;
        vec3 p = ro + rd * t;
        float env;
        vec3 d = density(p, env);
        float stepLen;
        if (d.x > 0.003) {
            // 稀薄处大步、浓处细步；云底整体再放大一点。步数预算不能浪费在几乎透明的尘雾里
            stepLen = mix(5.0, 0.9, smoothstep(0.0, 0.25, d.x)) * mix(1.0, 2.0, clamp(d.z, 0.0, 1.0));
            if (firstHit < 0.0) firstHit = t;
            float sigma = d.x * 0.55;
            float a = 1.0 - exp(-sigma * stepLen);

            // 自遮挡：沿主光方向 1~3 个样本
            float occl = densityOnly(p + sun * 3.0);
            if (ZQuality.y > 0.5) {
                occl += densityOnly(p + sun * 8.0) + 0.6 * densityOnly(p + sun * 18.0);
            } else {
                occl *= 2.2;
            }
            float Tsun = exp(-occl * 1.1);

            vec3 albedo = mix(mix(funCol, debCol, d.y), deckCol, d.z);
            // 高处混一点云底色，下段混碎屑色
            float zeta = clamp(p.y / H, 0.0, 1.0);
            albedo = mix(albedo, deckCol, smoothstep(0.45, 1.0, zeta) * 0.8 * (1.0 - d.z));
            // 环境光也受自遮挡：越厚越暗，云底才有层次而不是一块平板
            vec3 lit = albedo * (ambient * (0.35 + 0.25 * zeta) * exp(-occl * 0.25)
                                 + daylight * Tsun * (0.55 + 0.45 * phase * (1.0 - 0.6 * d.z)));
            lit += flash * vec3(0.85, 0.87, 1.0) * (0.35 + 0.65 * d.z) * smoothstep(0.4, 1.0, zeta) * (0.4 + 0.6 * Tsun);

            C += T * a * lit;
            T *= 1.0 - a;
        } else {
            // 空白区：按离最近包络的距离大步跳
            // 远处允许更粗的最小步（屏幕上一个像素覆盖的距离也随 t 变大）
            // env < 0 表示在某个包络里、但噪声让这里是空的：中等步长穿过去
            stepLen = env > 0.0 ? clamp(env * 0.8, 1.0 + t * 0.01, 28.0) : 2.2 + t * 0.015;
        }
        // 每步再抖一点，打散步进对齐造成的"木纹"等值线
        t += stepLen * (0.85 + 0.3 * hash3(vec3(gl_FragCoord.xy, float(i) + ZQuality.z)));
    }

    float alpha = (1.0 - T) * ZDeck.w;
    if (alpha < 0.003) discard;
    C *= ZDeck.w;

    // 原版线性雾（球形 / 柱形与原版一致）
    if (firstHit > 0.0) {
        vec3 hp = rd * firstHit;
        float fd = ZFog.z > 0.5 ? max(length(hp.xz), abs(hp.y)) : length(hp);
        float fv = fd < ZFog.x ? 0.0 : smoothstep(ZFog.x, ZFog.y, fd);
        C = mix(C, ZFogColor.rgb * alpha, fv * ZFogColor.a);
    }
    fragColor = vec4(C, alpha);
}
