"""Group the entries of the shipped host tree by package, so trimming has numbers.

用法: python tools/host-tree-size.py <path/to/host.zip>     (要裁体积之前先拿它出一组数)
"""
import collections
import os
import sys
import zipfile

path = sys.argv[1]
z = zipfile.ZipFile(path)
groups = collections.Counter()
counts = collections.Counter()
raw = collections.Counter()

for info in z.infolist():
    name = info.filename.lstrip('./')
    parts = name.split('/')
    # node_modules/@scope/pkg/...  ->  @scope/pkg ; otherwise the first two path parts
    if parts and parts[0] == 'node_modules':
        if len(parts) > 2 and parts[1].startswith('@'):
            key = 'node_modules/' + '/'.join(parts[1:3])
        elif len(parts) > 1:
            key = 'node_modules/' + parts[1]
        else:
            key = 'node_modules'
    else:
        key = '/'.join(parts[:2]) if len(parts) > 1 else (parts[0] if parts else '(root)')
    groups[key] += info.compress_size
    raw[key] += info.file_size
    counts[key] += 1

total = sum(groups.values())
print(f"{path}")
print(f"entries {len(z.infolist()):,}   compressed {total / 1024 / 1024:.1f} MiB   "
      f"uncompressed {sum(raw.values()) / 1024 / 1024:.1f} MiB")
print()
print(f"{'group':<52}{'comp MiB':>10}{'raw MiB':>10}{'files':>8}")
for key, size in groups.most_common(45):
    print(f"{key:<52}{size / 1024 / 1024:>10.2f}{raw[key] / 1024 / 1024:>10.2f}{counts[key]:>8}")
