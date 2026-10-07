"""量一颗球: 在整屏截图里找那团蓝紫渐变, 报它的包围盒

为什么要这个: 窗口坐标说"球在哪儿", 但**看得见多少**只有像素说了算 —— 半隐的定义是"球的一半在
屏幕外", 那是一个像素事实 (可见宽度 ≈ 118 px 的一半), 不是一句"看着像"。

用法: python tools/lw-ball-pixels.py <shot.png> [x0 y0 x1 y1]
不加区域就在整张图上找 (球是屏上唯一的蓝紫团)
"""

import sys
from PIL import Image

src = sys.argv[1]
area = tuple(int(v) for v in sys.argv[2:6]) if len(sys.argv) >= 6 else None

im = Image.open(src).convert('RGB')
if area:
    im = im.crop(area)

pixels = im.load()
xs = []
ys = []
for y in range(im.height):
    for x in range(im.width):
        r, g, b = pixels[x, y]
        # 蓝紫渐变那颗球: 浅底上实测 (169,185,255), 深色主题下叠了 0.5 透明再压暗成 (50,69,139) 那一档
        # —— 所以只比"蓝比红绿高多少", 不比绝对亮度 (白/浅灰的蓝红差是 0, 深色文字蓝不到 100)
        if b > 100 and b - r > 40 and b - g > 35:
            xs.append(x)
            ys.append(y)

if not xs:
    print('没找到那颗球')
    sys.exit(1)

left, right = min(xs), max(xs)
top, bottom = min(ys), max(ys)
if area:
    left += area[0]
    right += area[0]
    top += area[1]
    bottom += area[1]
print(f'{src}')
print(f'  可见包围盒: x {left}..{right} ({right - left + 1} px), y {top}..{bottom} ({bottom - top + 1} px)')
