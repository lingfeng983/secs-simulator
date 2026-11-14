#!/usr/bin/env python3
"""生成应用图标：深色圆角底 + 晶圆 + 信号弧。

输出：
  assets/icon.png          1024x1024，Tauri `tauri icon` 的源图
  assets/icon.ico          16..256 多尺寸，Windows exe / 任务栏
  assets/icon-<n>.png      16/32/48/64/128/256，窗口与托盘
"""

from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "assets"
SCALE = 2
SIZE = 1024
S = SIZE * SCALE

BG_TOP = (13, 23, 38)
BG_BOTTOM = (7, 13, 24)
ACCENT = (34, 211, 238)
ACCENT_DIM = (14, 116, 144)
WAFER = (8, 47, 73)
GREEN = (52, 211, 153)


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角底：竖向渐变，外圈留一点透明边给圆角
    pad = 24 * SCALE
    radius = 210 * SCALE
    for y in range(pad, S - pad):
        t = (y - pad) / max(1, S - 2 * pad)
        d.line([(pad, y), (S - pad, y)], fill=lerp(BG_TOP, BG_BOTTOM, t))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [pad, pad, S - pad, S - pad], radius=radius, fill=255
    )
    img.putalpha(mask)

    # 细网格：暗示设备/产线
    grid = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    step = 64 * SCALE
    for x in range(pad + step, S - pad, step):
        gd.line([(x, pad), (x, S - pad)], fill=(34, 211, 238, 12), width=1 * SCALE)
    for y in range(pad + step, S - pad, step):
        gd.line([(pad, y), (S - pad, y)], fill=(34, 211, 238, 12), width=1 * SCALE)
    img.alpha_composite(Image.composite(grid, Image.new("RGBA", (S, S), (0, 0, 0, 0)), mask))

    cx, cy = S // 2, S // 2
    r_out = 300 * SCALE
    r_in = 212 * SCALE
    ring = 26 * SCALE

    # 外环
    d.ellipse(
        [cx - r_out, cy - r_out, cx + r_out, cy + r_out],
        outline=ACCENT_DIM,
        width=ring,
    )
    # 开口的高亮弧（右上）
    d.arc(
        [cx - r_out, cy - r_out, cx + r_out, cy + r_out],
        start=-80,
        end=25,
        fill=ACCENT,
        width=ring,
    )
    # 内盘
    d.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=WAFER, outline=ACCENT, width=10 * SCALE)

    # 晶圆上的电路：几条折线
    trace = ACCENT
    w = 12 * SCALE
    d.line([(cx - 150 * SCALE, cy + 40 * SCALE), (cx - 60 * SCALE, cy + 40 * SCALE),
            (cx - 20 * SCALE, cy), (cx + 30 * SCALE, cy)], fill=trace, width=w)
    d.line([(cx - 100 * SCALE, cy + 120 * SCALE), (cx - 40 * SCALE, cy + 120 * SCALE),
            (cx + 10 * SCALE, cy + 70 * SCALE), (cx + 90 * SCALE, cy + 70 * SCALE)],
           fill=trace, width=w)
    d.line([(cx - 120 * SCALE, cy - 50 * SCALE), (cx - 40 * SCALE, cy - 50 * SCALE),
            (cx + 10 * SCALE, cy - 100 * SCALE)], fill=ACCENT_DIM, width=w)
    for x, y in [
        (cx - 150 * SCALE, cy + 40 * SCALE),
        (cx + 30 * SCALE, cy),
        (cx - 100 * SCALE, cy + 120 * SCALE),
        (cx + 90 * SCALE, cy + 70 * SCALE),
        (cx - 120 * SCALE, cy - 50 * SCALE),
        (cx + 10 * SCALE, cy - 100 * SCALE),
    ]:
        rr = 18 * SCALE
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=ACCENT)

    # 三圈信号弧（右上角，从晶圆发出）
    for i, rr in enumerate((r_out + 70 * SCALE, r_out + 130 * SCALE, r_out + 190 * SCALE)):
        box = [cx - rr, cy - rr, cx + rr, cy + rr]
        d.arc(box, start=-62, end=-8, fill=(ACCENT[0], ACCENT[1], ACCENT[2], 220 - i * 45),
              width=(18 - i * 4) * SCALE)

    # 右下状态点
    dx, dy = cx + 250 * SCALE, cy + 250 * SCALE
    dr = 46 * SCALE
    d.ellipse([dx - dr, dy - dr, dx + dr, dy + dr], fill=GREEN)

    icon = img.resize((SIZE, SIZE), Image.LANCZOS)
    icon.save(OUT / "icon.png", "PNG", optimize=True)
    for n in (16, 32, 48, 64, 128, 256):
        icon.resize((n, n), Image.LANCZOS).save(OUT / f"icon-{n}.png", "PNG", optimize=True)
    icon.save(OUT / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                                       (64, 64), (128, 128), (256, 256)])
    print("written:", OUT)


if __name__ == "__main__":
    main()
