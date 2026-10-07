"""把启动器图标那两件事一次算清: 矢量前景的墨迹框, 以及它该用多大的 scale/translate

为什么需要这个脚本 (2026-10-05 主人: "图标在手机上被放大了"): 自适应图标那两份
(`drawable/ic_launcher_foreground.xml` + `mipmap-anydpi/ic_launcher.xml`) 才是手机上真正画出来的
那一个, 而它是**手写的 group 变换** (scaleX/translateX 那几个数)。猜那些数只能得到"看着差不多",
而这个脚本把"该多大"变成一道算式:

1. 参考真值: dsh 自己的图标 (`apps/desktop/resources/icon.png`), 量出鲸鱼在它里面占的比例 ——
   那个比例就是"手机上看到的图标"该有的样子
2. 把参考比例换算到 108x108 的 viewBox 上, 得到**目标墨迹框**
3. 解析 `ic_launcher_foreground.xml` 里那条 pathData 的真实包围盒 (曲线按长度采样, 不是只看端点)
4. 解出让它落进目标框的 scale 与 translate, 并核对它确实在自适应图标的安全区里

用法:
  python tools/vector-ink.py                  # 只报数 (当前变换 vs 算出来的变换)
  python tools/vector-ink.py --write          # 把算出来的 scale/translate 写回那份矢量
  python tools/vector-ink.py --preview out.png  # 顺手画一张对照图 (左: 现在, 右: 算出来的)

**不进构建**: 与 tools/make-icons.py 一样是手工跑一次的工具, 产物提交进仓库
"""

import argparse
import math
import re
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover - 只在没装 Pillow 的机器上会走到
    sys.exit('this script needs Pillow: python -m pip install Pillow')

# 自适应图标的两个约定 (Android 官方): 108x108 的 viewBox 里, 可见范围是中间 72x72, 而外围
# 那 18 一边是给视差与遮罩留的 —— 安全区再收一点 (66x66) 才稳
VIEWPORT = 108.0
VISIBLE = 72.0
SAFE = 66.0

# 参考用的墨迹阈值: 与 tools/make-icons.py 同一个数 (那里量通知小图标也是它)
INK_THRESHOLD = 100


def sample_path(path_data):
    """把 pathData 摊成点集: 曲线按长度采样, 于是包围盒是真包围盒

    只做"量框"这一件事需要的部分: 绝对/相对的命令都归一到绝对坐标, 曲线每条取若干点。解析不出来
    的命令直接报错而不是跳过 —— 跳过会让框偏小, 而偏小的框会把图标放大
    """
    tokens = re.findall(r'[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:e-?\d+)?', path_data)
    points = []
    at = 0
    # 当前点 / 上一次的控制点 (相对命令要按它们算)
    x = y = 0.0
    start = (0.0, 0.0)
    last_cubic = None
    last_quad = None
    command = None

    def number():
        nonlocal at
        value = float(tokens[at])
        at += 1
        return value

    def cubic(p0, p1, p2, p3, steps=48):
        out = []
        for index in range(1, steps + 1):
            t = index / steps
            u = 1 - t
            out.append((
                u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
                u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
            ))
        return out

    def quad(p0, p1, p2, steps=32):
        out = []
        for index in range(1, steps + 1):
            t = index / steps
            u = 1 - t
            out.append((
                u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
            ))
        return out

    while at < len(tokens):
        token = tokens[at]
        if re.match(r'[A-Za-z]', token):
            command = token
            at += 1
            if command in 'Zz':
                x, y = start
                points.append((x, y))
                last_cubic = last_quad = None
                continue
        elif command is None:
            raise SystemExit(f'unexpected path token {token!r} before any command')

        relative = command.islower()
        letter = command.upper()

        if letter == 'M':
            nx, ny = number(), number()
            x, y = (x + nx, y + ny) if relative else (nx, ny)
            start = (x, y)
            points.append((x, y))
            command = 'l' if relative else 'L'  # 后面跟着的坐标对是 lineto
            last_cubic = last_quad = None
        elif letter == 'L':
            nx, ny = number(), number()
            x, y = (x + nx, y + ny) if relative else (nx, ny)
            points.append((x, y))
            last_cubic = last_quad = None
        elif letter == 'H':
            nx = number()
            x = x + nx if relative else nx
            points.append((x, y))
            last_cubic = last_quad = None
        elif letter == 'V':
            ny = number()
            y = y + ny if relative else ny
            points.append((x, y))
            last_cubic = last_quad = None
        elif letter == 'C':
            x1, y1 = number(), number()
            x2, y2 = number(), number()
            nx, ny = number(), number()
            if relative:
                x1, y1, x2, y2, nx, ny = x + x1, y + y1, x + x2, y + y2, x + nx, y + ny
            points.extend(cubic((x, y), (x1, y1), (x2, y2), (nx, ny)))
            last_cubic = (x2, y2)
            last_quad = None
            x, y = nx, ny
        elif letter == 'S':
            x2, y2 = number(), number()
            nx, ny = number(), number()
            if relative:
                x2, y2, nx, ny = x + x2, y + y2, x + nx, y + ny
            if last_cubic is None:
                x1, y1 = x, y
            else:
                x1, y1 = 2 * x - last_cubic[0], 2 * y - last_cubic[1]
            points.extend(cubic((x, y), (x1, y1), (x2, y2), (nx, ny)))
            last_cubic = (x2, y2)
            last_quad = None
            x, y = nx, ny
        elif letter == 'Q':
            x1, y1 = number(), number()
            nx, ny = number(), number()
            if relative:
                x1, y1, nx, ny = x + x1, y + y1, x + nx, y + ny
            points.extend(quad((x, y), (x1, y1), (nx, ny)))
            last_quad = (x1, y1)
            last_cubic = None
            x, y = nx, ny
        elif letter == 'T':
            nx, ny = number(), number()
            if relative:
                nx, ny = x + nx, y + ny
            if last_quad is None:
                x1, y1 = x, y
            else:
                x1, y1 = 2 * x - last_quad[0], 2 * y - last_quad[1]
            points.extend(quad((x, y), (x1, y1), (nx, ny)))
            last_quad = (x1, y1)
            last_cubic = None
            x, y = nx, ny
        elif letter == 'A':
            rx, ry, rotation = number(), number(), number()
            large, sweep, nx, ny = number(), number(), number(), number()
            if relative:
                nx, ny = x + nx, y + ny
            # 鲸鱼这条路径里没有弧 —— 真出现了就直说, 别猜一个框出来
            raise SystemExit('this path has an arc command; the bounding box would need a real flattener')
        else:
            raise SystemExit(f'unsupported path command {command!r}')

    if not points:
        raise SystemExit('the path produced no points')
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return min(xs), min(ys), max(xs), max(ys)


def reference_box(source_path):
    """参考图标里鲸鱼占的那块框, 换算到 108 的 viewBox 上"""
    image = Image.open(source_path).convert('RGBA')
    flat = Image.alpha_composite(Image.new('RGBA', image.size, (255, 255, 255, 255)), image)
    mask = flat.convert('L').point(lambda value: 255 if value < INK_THRESHOLD else 0, mode='1')
    box = mask.getbbox()
    if box is None:
        raise SystemExit(f'{source_path} has no dark ink at all')
    scale = VIEWPORT / image.size[0]
    return tuple(value * scale for value in box), image.size


def main():
    parser = argparse.ArgumentParser(description='Size the adaptive icon foreground from the reference icon')
    parser.add_argument('--vector', default='app/src/main/res/drawable/ic_launcher_foreground.xml')
    parser.add_argument('--source', default='third_party/deepseek-harness/apps/desktop/resources/icon.png')
    parser.add_argument('--write', action='store_true', help='write the solved transform back into the vector')
    parser.add_argument('--preview', metavar='PNG', help='draw both versions for comparison')
    # **目标宽度**才是主人要的那个数 (2026-10-06): dsh 自己的图标把鲸鱼放到画布的 72.6%, 而那在
    # 手机桌面上显得比别人的图标都大 —— 主人两次要求缩小, 所以现在按"正常自适应图标"的口径来:
    # keyline 那圈是 44/108 = 0.407, 这里取 0.46 留一点余量
    parser.add_argument(
        '--ink-width', type=float, default=0.46,
        help='target ink width as a fraction of the 108 canvas (default 0.46, the adaptive-icon keyline '
             'scale; dsh\'s own icon.png measures 0.726 and is too large on a home screen)',
    )
    arguments = parser.parse_args()

    vector_path = Path(arguments.vector)
    text = vector_path.read_text(encoding='utf-8')

    group = re.search(r'<group([^>]*)>', text, re.S)
    path = re.search(r'android:pathData="([^"]+)"', text, re.S)
    if group is None or path is None:
        raise SystemExit(f'{vector_path} has no <group> / pathData to work with')

    def attribute(name):
        match = re.search(rf'android:{name}="(-?[\d.]+)"', group.group(1))
        return float(match.group(1)) if match else None

    scale_now = attribute('scaleX')
    translate_x = attribute('translateX')
    translate_y = attribute('translateY')
    if scale_now is None or translate_x is None or translate_y is None:
        raise SystemExit('the group needs scaleX, translateX and translateY for this script')

    left, top, right, bottom = sample_path(path.group(1))
    raw_width, raw_height = right - left, bottom - top
    print(f'path ink box (viewBox units): {left:.2f},{top:.2f} -> {right:.2f},{bottom:.2f}'
          f'  = {raw_width:.2f} x {raw_height:.2f}')

    target, source_size = reference_box(Path(arguments.source))
    target_width, target_height = target[2] - target[0], target[3] - target[1]
    print(f'reference {arguments.source} {source_size[0]}x{source_size[1]}')
    print(f'  ink box on a {VIEWPORT:.0f} viewBox: {target[0]:.2f},{target[1]:.2f} -> '
          f'{target[2]:.2f},{target[3]:.2f}  = {target_width:.2f} x {target_height:.2f}'
          f'  ({target_width / VIEWPORT:.3f} x {target_height / VIEWPORT:.3f} of the canvas)')

    # **目标是主人点的那条**: 墨迹宽度占画布多少 (默认 0.46)。参考图那条只用来报数, 不再当权威 ——
    # dsh 自己的图标是 0.726, 那在桌面上显得比别人的图标都大
    wanted_width = arguments.ink_width * VIEWPORT
    wanted_height = wanted_width * (raw_height / raw_width)
    solved_scale = wanted_width / raw_width
    solved_x = (VIEWPORT - wanted_width) / 2 - solved_scale * left
    solved_y = (VIEWPORT - wanted_height) / 2 - solved_scale * top
    print(f'target ink {wanted_width:.2f} x {wanted_height:.2f} wide (--ink-width {arguments.ink_width})')

    def report(tag, scale, tx, ty):
        drawn = (scale * left + tx, scale * top + ty, scale * right + tx, scale * bottom + ty)
        width, height = drawn[2] - drawn[0], drawn[3] - drawn[1]
        inside_safe = all(
            0 <= value <= VIEWPORT for value in drawn
        ) and width <= VISIBLE and height <= VISIBLE
        print(f'  {tag}: scale={scale:.4f} translate=({tx:.4f}, {ty:.4f})'
              f'  -> ink {drawn[0]:.2f},{drawn[1]:.2f} = {width:.2f} x {height:.2f}'
              f'  ({width / VIEWPORT:.3f} x {height / VIEWPORT:.3f})'
              f'  {"在可见范围里" if inside_safe else "**超出可见范围**"}')
        return drawn

    print('现在:')
    drawn_now = report('as is', scale_now, translate_x, translate_y)
    print('按参考图算出来:')
    drawn_solved = report('solved', solved_scale, solved_x, solved_y)
    print(f'  缩放比: {solved_scale / scale_now:.3f} 倍 (现在比参考大 '
          f'{(drawn_now[2] - drawn_now[0]) / max(0.001, drawn_solved[2] - drawn_solved[0]):.2f} 倍)')

    if arguments.write:
        replaced = text
        replaced = re.sub(r'android:scaleX="[\d.]+"', f'android:scaleX="{solved_scale:.4f}"', replaced)
        replaced = re.sub(r'android:scaleY="[\d.]+"', f'android:scaleY="{solved_scale:.4f}"', replaced)
        replaced = re.sub(r'android:translateX="[\d.]+"', f'android:translateX="{solved_x:.4f}"', replaced)
        replaced = re.sub(r'android:translateY="[\d.]+"', f'android:translateY="{solved_y:.4f}"', replaced)
        if replaced == text:
            raise SystemExit('nothing changed: the group attributes are not in the shape this script expects')
        # **一定要 LF**: 仓库是 `* text=auto eol=lf`, 而 aapt2 在 CRLF 的 XML 上会报
        # `ParseError at [row,col]` (2026-10-06 踩过: 写回之后图标资源编译不过)。Windows 上
        # `write_text` 默认会把 \n 翻成 \r\n, 所以这里显式给 newline
        #
        # **也不许出现双横线**: XML 注释里不能有 `--`, 而 aapt2 报的是同一句 ParseError —— 所以
        # 上面那段注释里写参数名时用引号或改写, 别直接抄命令行 (这个坑一天踩了两次)
        vector_path.write_text(replaced, encoding='utf-8', newline='')
        print(f'wrote {vector_path}')

    if arguments.preview:
        width = int(VIEWPORT) * 2 + 30
        height = int(VIEWPORT) + 60
        preview = Image.new('RGBA', (width, height), (255, 255, 255, 255))
        draw = ImageDraw.Draw(preview)
        for index, drawn in enumerate((drawn_now, drawn_solved)):
            origin = (10 + index * (VIEWPORT + 10), 40)
            draw.rectangle(
                (origin[0], origin[1] + 0, origin[0] + VIEWPORT, origin[1] + VIEWPORT),
                fill=(226, 232, 240),
            )
            # 可见范围 (中间 72) 与安全区 (66) 各画一圈: 超出哪一圈一眼看得出
            draw.rectangle(
                (origin[0] + 18, origin[1] + 18, origin[0] + VIEWPORT - 18, origin[1] + VIEWPORT - 18),
                outline=(255, 120, 120),
            )
            draw.rectangle(
                (origin[0] + 21, origin[1] + 21, origin[0] + VIEWPORT - 21, origin[1] + VIEWPORT - 21),
                outline=(120, 200, 120),
            )
            draw.rectangle(
                (origin[0] + drawn[0], origin[1] + drawn[1], origin[0] + drawn[2], origin[1] + drawn[3]),
                outline=(40, 80, 220),
                width=2,
            )
            draw.text((origin[0], 18), 'as is' if index == 0 else 'solved', fill=(20, 20, 20))
        preview.save(arguments.preview)
        print(f'wrote {arguments.preview}')

    return 0


if __name__ == '__main__':
    raise SystemExit(main())
