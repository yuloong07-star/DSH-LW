"""扫 strings.xml 里的裸 % —— Android 的 Resources.getString 不认 %% 转义

踩过的坑 (2026-10-06): `<string name="x">%1$d%%; 100% 是...</string>` 这种写法在 `String.format`
里没事, 而 `Resources.getString` 走到 `Formatter` 时会把 `%;` 当成一个转换符, 抛
`UnknownFormatConversionException: Conversion = '是'` —— 主线程崩, 整个应用重启

判据: 一个 % 后面如果既不是 `%`, 也不是 `数字$` 开头、也不是 `s/d/f/...` 这类转换符, 那它就是一个
裸 %, 必须写成 `%%` (或者给那条 string 加 formatted="false")

跑法: python tools/check-bare-percent.py     (扫 app/src/main/res/values*/strings.xml)
"""

import re
import sys
from pathlib import Path

CONVERSIONS = set("bBhHsScCdoxXeEfgGaAtTn%")
SPEC = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")
ENTRY = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)


def problems(path):
    text = Path(path).read_text(encoding="utf-8")
    bad = []
    for name, attrs, body in ENTRY.findall(text):
        if 'formatted="false"' in attrs:
            continue
        pos = 0
        while True:
            index = body.find("%", pos)
            if index < 0:
                break
            # `%%` 是合法的转义对, 直接跳过整对 —— 这里曾经把它也报成一条, 结果是假阳性
            if body.startswith("%%", index):
                pos = index + 2
                continue
            match = SPEC.match(body, index)
            ok = bool(match) and match.group(5) in CONVERSIONS
            if not ok:
                tail = body[index:index + 12].replace("\n", " ")
                bad.append((name, tail))
            pos = index + 1
    return bad


total = 0
for path in sys.argv[1:]:
    found = problems(path)
    print(f"{path}: {len(found)} bare %")
    for name, tail in found:
        print(f'    {name:<38} {tail!r}')
    total += len(found)
print(f"\ntotal: {total}")
