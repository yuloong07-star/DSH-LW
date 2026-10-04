"""Find the bytes in an APK that no zip entry accounts for."""
import os
import sys
import zipfile

path = sys.argv[1]
size = os.path.getsize(path)
z = zipfile.ZipFile(path)
infos = sorted(z.infolist(), key=lambda i: i.header_offset)

total_c = sum(i.compress_size for i in infos)
total_u = sum(i.file_size for i in infos)
print(f"file            : {size:>12,} bytes  ({size / 1024 / 1024:.1f} MiB)")
print(f"sum compressed  : {total_c:>12,} bytes  ({total_c / 1024 / 1024:.1f} MiB)")
print(f"sum uncompressed: {total_u:>12,} bytes  ({total_u / 1024 / 1024:.1f} MiB)")
print(f"central dir at  : {z.start_dir:>12,}")
print(f"entries         : {len(infos)}")
print()

# Walk the local headers in file order and look for anything not covered by an entry.
holes = []
cursor = 0
for info in infos:
    start = info.header_offset
    if start > cursor:
        holes.append((cursor, start - cursor, "before " + info.filename))
    header = 30 + len(info.filename.encode("utf-8")) + len(info.extra)
    end = start + header + info.compress_size
    if info.flag_bits & 0x08:
        end += 16  # data descriptor, generous
    if end > cursor:
        cursor = end
if z.start_dir > cursor:
    holes.append((cursor, z.start_dir - cursor, "before the central directory"))

holes.sort(key=lambda h: -h[1])
print("largest unaccounted regions:")
for offset, length, what in holes[:15]:
    print(f"  {offset:>12,} .. {offset + length:>12,}  {length:>12,} bytes  ({length / 1024 / 1024:6.1f} MiB)  {what}")
print()
print(f"total unaccounted: {sum(h[1] for h in holes):,} bytes "
      f"({sum(h[1] for h in holes) / 1024 / 1024:.1f} MiB)")
print(f"signature block + EOCD tail: {size - z.start_dir:,} bytes "
      f"({(size - z.start_dir) / 1024 / 1024:.1f} MiB)")
