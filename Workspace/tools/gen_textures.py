#!/usr/bin/env python3
"""生成 Zenith 占位贴图（16x16 方块/物品，8x8 粒子），纯标准库写 PNG。"""
import os, zlib, struct, math, random

ROOT = os.path.expanduser("~/workspace/zenith/src/main/resources/assets/zenith/textures")
random.seed(20261006)

def write_png(path, w, h, pixels):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        c += struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff)
        return c
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *p) for p in row) for row in pixels)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)
    print("wrote", path)

def shade(base, amt):
    return tuple(max(0, min(255, c + amt)) for c in base[:3]) + (255,)

def metal(base, x, y, w, h):
    """拉丝金属底"""
    px = []
    for j in range(h):
        row = []
        for i in range(w):
            n = random.randint(-8, 8)
            v = math.sin((i + j) * 0.7) * 6
            row.append(shade(base, int(n + v)))
        px.append(row)
    # 边框
    for i in range(w):
        px[0][i] = shade(base, -60); px[h-1][i] = shade(base, -60)
    for j in range(h):
        px[j][0] = shade(base, -60); px[j][w-1] = shade(base, -60)
    return px

# ---- 方块贴图 16x16 ----
fan_side = metal((120, 128, 140), 0, 0, 16, 16)
# 通风格栅
for j in range(3, 13):
    for i in range(3, 13):
        if (j % 3 == 0):
            fan_side[j][i] = shade((120,128,140), -70)
write_png(f"{ROOT}/block/fan_side.png", 16, 16, fan_side)

fan_front = metal((105, 112, 125), 0, 0, 16, 16)
# 扇叶示意：旋转叶片
cx, cy = 7.5, 7.5
for j in range(16):
    for i in range(16):
        dx, dy = i - cx, j - cy
        r = math.hypot(dx, dy)
        if r < 6.5:
            ang = (math.degrees(math.atan2(dy, dx)) + r * 28) % 360
            if int(ang / 90) % 2 == 0:
                fan_front[j][i] = shade((150, 160, 175), 10)
            else:
                fan_front[j][i] = shade((70, 78, 90), 0)
        if r < 1.8:
            fan_front[j][i] = shade((60, 66, 76), 0)
write_png(f"{ROOT}/block/fan_front.png", 16, 16, fan_front)

duct_side = metal((110, 105, 95), 0, 0, 16, 16)
for i in range(16):  # 法兰环
    duct_side[0][i] = shade((110,105,95), -55)
    duct_side[15][i] = shade((110,105,95), -55)
    duct_side[7][i] = shade((110,105,95), -35)
    duct_side[8][i] = shade((110,105,95), -35)
write_png(f"{ROOT}/block/duct_side.png", 16, 16, duct_side)
duct_end = metal((110, 105, 95), 0, 0, 16, 16)
for j in range(16):
    for i in range(16):
        if 4 <= i < 12 and 4 <= j < 12:
            duct_end[j][i] = (18, 16, 14, 255)  # 管口黑洞
write_png(f"{ROOT}/block/duct_end.png", 16, 16, duct_end)

vane = metal((140, 130, 115), 0, 0, 16, 16)
write_png(f"{ROOT}/block/wind_vane.png", 16, 16, vane)

probe = metal((125, 120, 110), 0, 0, 16, 16)
# 涡轮叶片示意
for j in range(4, 12):
    for i in range(4, 12):
        if (i + j) % 4 < 2:
            probe[j][i] = shade((160, 155, 140), 15)
write_png(f"{ROOT}/block/wind_turbine_probe.png", 16, 16, probe)

# ---- 物品贴图 16x16 ----
def item_base(draw):
    px = [[(0,0,0,0) for _ in range(16)] for _ in range(16)]
    draw(px)
    return px

def meter(px):
    for j in range(16):
        for i in range(16):
            dx, dy = i - 7.5, j - 7.5
            r = math.hypot(dx, dy)
            if r < 7:
                px[j][i] = shade((235, 230, 210), -int(r * 4))
    # 表盘刻度
    for a in range(0, 360, 30):
        r1, r2 = 4.5, 6
        x1 = int(7.5 + math.cos(math.radians(a)) * r1); y1 = int(7.5 + math.sin(math.radians(a)) * r1)
        x2 = int(7.5 + math.cos(math.radians(a)) * r2); y2 = int(7.5 + math.sin(math.radians(a)) * r2)
        if 0 <= x2 < 16 and 0 <= y2 < 16:
            px[y2][x2] = (40, 40, 40, 255)
    # 指针
    for t in range(5):
        x = int(7.5 + t * 0.9); y = int(7.5 - t * 0.4)
        if 0 <= x < 16 and 0 <= y < 16:
            px[y][x] = (200, 40, 40, 255)
write_png(f"{ROOT}/item/wind_meter.png", 16, 16, item_base(meter))

def map_item(px):
    for j in range(2, 14):
        for i in range(2, 14):
            px[j][i] = shade((210, 200, 170), random.randint(-6, 6))
    for i in range(2, 14):
        px[2][i] = shade((210,200,170), -60); px[13][i] = shade((210,200,170), -60)
    for j in range(2, 14):
        px[j][2] = shade((210,200,170), -60); px[j][13] = shade((210,200,170), -60)
    # 等压线
    for k, rr in enumerate([2.5, 4.5]):
        for a in range(0, 360, 6):
            x = int(7.5 + math.cos(math.radians(a)) * rr); y = int(7.5 + math.sin(math.radians(a)) * rr)
            if 2 <= x < 14 and 2 <= y < 14:
                px[y][x] = (60, 90, 160, 255)
    px[7][7] = (200, 40, 40, 255); px[7][8] = (200, 40, 40, 255)  # 低压中心
write_png(f"{ROOT}/item/meteorological_map.png", 16, 16, item_base(map_item))

def sailboat(px):
    for i in range(3, 13):  # 船体
        for j in range(10, 13):
            px[j][i] = shade((120, 80, 45), random.randint(-8, 8))
    for j in range(3, 10):  # 桅杆
        px[j][7] = shade((90, 65, 40), 0); px[j][8] = shade((90, 65, 40), 0)
    for j in range(3, 10):  # 帆
        w = int((j - 3) * 0.9)
        for i in range(8, 8 + w):
            if i < 16:
                px[j][i] = shade((240, 235, 220), random.randint(-6, 6))
write_png(f"{ROOT}/item/sailboat.png", 16, 16, item_base(sailboat))

# ---- 粒子贴图 8x8 ----
def soft_circle(color, alpha_center):
    px = []
    for j in range(8):
        row = []
        for i in range(8):
            r = math.hypot(i - 3.5, j - 3.5) / 3.5
            a = max(0, min(255, int(alpha_center * (1 - r * r))))
            row.append(color + (a,))
        px.append(row)
    return px

write_png(f"{ROOT}/particle/wind_streak.png", 8, 8,
          [[(255, 255, 255, 200 if 3 <= j <= 4 else 60) for i in range(8)] for j in range(8)])
write_png(f"{ROOT}/particle/leaf.png", 8, 8, soft_circle((90, 160, 60), 255))
write_png(f"{ROOT}/particle/dust.png", 8, 8, soft_circle((185, 160, 120), 160))
write_png(f"{ROOT}/particle/spray.png", 8, 8, soft_circle((215, 235, 255), 220))
write_png(f"{ROOT}/particle/heat_shimmer.png", 8, 8, soft_circle((255, 255, 255), 60))
print("done")
