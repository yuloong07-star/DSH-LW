"""把 DSH 的官方图标做成 Android 要的那几档位图

为什么需要这个脚本: 这个仓库里原来的 `mipmap-*dpi/*.webp` 是 Android Studio 生成的默认占位图
(绿机器人), 而清单里 `android:icon` 指向的正是 `mipmap/ic_launcher` —— 自适应图标那两份
(`mipmap-anydpi/ic_launcher.xml`) 内容是对的, 但任何回退到密度位图的地方拿到的都是占位图,
所以别处看到的图标还是默认的。位图不能靠手画, 于是把它做成可复现的一步

源图是子模块里 dsh 自己的应用图标 (`apps/desktop/resources/icon.png`), 不是我们自己画的:
那只鲸鱼是 DSH 的标记, 与 `drawable/ic_launcher_foreground.xml` 里那条 pathData 是同一个形状

用法:
  python tools/make-icons.py
  python tools/make-icons.py --source <icon.png> --res <app/src/main/res>

生成:
  mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.webp        方形, 背景是官方那层浅蓝灰
  mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.webp  同样内容裁成圆
  drawable/ic_notification.png                        24dp 单色小图标, 通知栏用

**不进构建**: 这几个文件是提交进仓库的产物, Gradle 不认识这个脚本, 换图标时手工跑一次
"""

import argparse
import shutil
import sys
from collections import deque
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover - 只在没装 Pillow 的机器上会走到
    sys.exit('this script needs Pillow: python -m pip install Pillow')

# Android 的密度档与它对应的启动器图标边长, 单位是像素
DENSITIES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

# 通知小图标是 24dp, 而通知图标取密度最高的那一档就够: 系统自己会按屏幕缩放
NOTIFICATION_PX = 96

# 判定"这是鲸鱼而不是背景"的阈值。官方图标的底是浅色加一层淡淡的渐变 (合成到白底之后最亮
# 的地方接近 215), 鲸鱼自己的最深处在 44 左右, 所以阈值取在两者之间。实测 80 到 140 之间形状
# 完全一样, 取 100 是为了留出两边的余量
INK_THRESHOLD = 100

# 通知小图标的安全区: 24dp 里图案只占中间这一段, 四周留白, 免得被裁到
NOTIFICATION_INSET = 0.12


def whale_mask(image):
    """哪几个像素是鲸鱼

    源图带着投影与一圈很淡的底色, 光看"比阈值暗"会把投影也收进来 —— 那样裁切框会撑到整张图,
    鲸鱼就缩得只剩一点点。鲸鱼本身是最大的一块连通暗区, 所以这里只留最大的那一块

    先合成到白底上再取亮度: 源图是透明底的, 直接转灰度会把透明的部分当成黑色
    """
    flat = Image.alpha_composite(Image.new('RGBA', image.size, (255, 255, 255, 255)), image)
    grey = flat.convert('L')
    mask = grey.point(lambda value: 255 if value < INK_THRESHOLD else 0, mode='1')
    keep_largest_blob(mask)
    return mask


def keep_largest_blob(mask):
    """只留最大的一块连通区域, 其余的清掉, 就地改 mask"""
    width, height = mask.size
    pixels = mask.load()
    seen = bytearray(width * height)
    largest = []

    for start_y in range(height):
        for start_x in range(width):
            if not pixels[start_x, start_y] or seen[start_y * width + start_x]:
                continue
            blob = []
            queue = deque([(start_x, start_y)])
            seen[start_y * width + start_x] = 1
            while queue:
                x, y = queue.popleft()
                blob.append((x, y))
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if 0 <= nx < width and 0 <= ny < height:
                        if pixels[nx, ny] and not seen[ny * width + nx]:
                            seen[ny * width + nx] = 1
                            queue.append((nx, ny))
            if len(blob) > len(largest):
                largest = blob

    # 留下的才是最大那块, 所以清掉的是**不在**里面的 (反过来写等于把要留的清掉了)
    keep = set(largest)
    for y in range(height):
        for x in range(width):
            if pixels[x, y] and (x, y) not in keep:
                pixels[x, y] = 0


def mark_box(mask):
    """鲸鱼实际占的那块矩形, 兜底是整张图"""
    return mask.getbbox() or (0, 0, mask.width, mask.height)


def glyph(source, mask, size, inset, ink=False):
    """把鲸鱼单独抠出来, 缩放并居中放进一块 size x size 的透明画布

    居中按**裁切框**算, 不按鲸鱼自己的边框算: 鲸鱼比它那块框矮, 按边框居中会让它整体靠在
    下边。通知栏那个位置只有 24dp, 偏一点看得出来

    `ink` 只是给人看的: 通知小图标交给系统时必须是一层白模 (系统拿它当模板染色), 所以默认
    是白的 —— 而白的在白底预览图上什么也看不见, 要检查形状就把它染黑
    """
    box = mark_box(mask)
    patch_mask = mask.crop(box)
    inner = max(1, round(size * (1 - 2 * inset)))
    scale = inner / max(patch_mask.width, patch_mask.height)
    target = (max(1, round(patch_mask.width * scale)), max(1, round(patch_mask.height * scale)))
    patch_mask = patch_mask.resize(target, Image.LANCZOS).convert('L')

    colour = (0, 0, 0, 255) if ink else (255, 255, 255, 255)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    patch = Image.new('RGBA', target, colour)
    patch.putalpha(patch_mask)
    canvas.paste(patch, ((size - target[0]) // 2, (size - target[1]) // 2), patch)
    return canvas


def launcher_icon(source, size, round_corners):
    """一档启动器图标: 官方那张图直接缩到这一档, 需要时裁成圆

    不做抠图重排: 源图自己的留白就是给自适应图标的安全区留的, 直接缩放出来与
    `mipmap-anydpi/ic_launcher.xml` 那两份 (背景渐变 + 鲸鱼前景) 看起来是同一个图标
    """
    icon = source.resize((size, size), Image.LANCZOS)
    if round_corners:
        # 清单里 `roundIcon` 要的就是一个圆的, 裁在这里比让系统裁更可控
        mask = Image.new('L', (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        icon.putalpha(mask)
    return icon


def main():
    parser = argparse.ArgumentParser(description='Render the Android launcher and notification icons')
    parser.add_argument(
        '--source',
        default='third_party/deepseek-harness/apps/desktop/resources/icon.png',
        help="dsh's own app icon, which the whale mark comes from",
    )
    parser.add_argument('--res', default='app/src/main/res', help='where the drawables live')
    parser.add_argument('--lock-screen', metavar='SRC', help='also install SRC as the lock screen wallpaper')
    parser.add_argument('--background', metavar='SRC', help='also install SRC as the home screen wallpaper')
    parser.add_argument('--preview', metavar='DIR', help='write PNG copies of everything here, for looking at')
    arguments = parser.parse_args()

    source_path = Path(arguments.source)
    if not source_path.is_file():
        sys.exit(f'no such source image: {source_path}')
    res = Path(arguments.res)
    if not res.is_dir():
        sys.exit(f'no such resource directory: {res}')

    source = Image.open(source_path).convert('RGBA')
    print(f'source {source_path} {source.width}x{source.height}')

    preview = Path(arguments.preview) if arguments.preview else None
    if preview:
        preview.mkdir(parents=True, exist_ok=True)

    for density, size in DENSITIES.items():
        directory = res / f'mipmap-{density}'
        directory.mkdir(parents=True, exist_ok=True)
        for name, rounded in (('ic_launcher', False), ('ic_launcher_round', True)):
            icon = launcher_icon(source, size, rounded)
            target = directory / f'{name}.webp'
            icon.save(target, 'WEBP', lossless=True, quality=100)
            if preview:
                icon.save(preview / f'{density}-{name}.png')
            print(f'  {target} {size}x{size}')

    notification = glyph(source, whale_mask(source), NOTIFICATION_PX, inset=NOTIFICATION_INSET)
    drawable = res / 'drawable'
    drawable.mkdir(parents=True, exist_ok=True)
    notification_path = drawable / 'ic_notification.png'
    notification.save(notification_path, 'PNG', optimize=True)
    if preview:
        # 预览把形状染黑: 白色的白模放在白底看图工具里等于一张空图
        glyph(source, whale_mask(source), NOTIFICATION_PX, inset=NOTIFICATION_INSET, ink=True).save(
            preview / 'ic_notification-ink.png'
        )
        notification.save(preview / 'ic_notification.png')
    print(f'  {notification_path} {NOTIFICATION_PX}x{NOTIFICATION_PX}')

    # 两样壁纸是手工步骤的产物: 源图给进来就顺手放到 res 里, 没给就不动, 免得把旧图覆盖成空的
    for option, name in (('lock_screen', 'lock_screen.png'), ('background', 'background.png')):
        path = getattr(arguments, option)
        if not path:
            continue
        shutil.copyfile(path, drawable / name)
        print(f'  {drawable / name}')

    print('done, now build: .\\gradlew.bat :app:assembleDebug')


if __name__ == '__main__':
    main()
