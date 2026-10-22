"""압축하지 않은 항목의 데이터를 4바이트 경계에 맞춰 다시 쓴다(zipalign -p 4 와 같은 효과).
사용법: python3 zipalign.py <입력.zip> <출력.zip>"""
import struct
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, open(dst, 'wb') as out:
    central = []
    for info in zin.infolist():
        data = zin.open(info).read() if info.compress_type == zipfile.ZIP_STORED else None
        raw = None
        if data is None:
            # 압축된 데이터는 그대로 복사한다
            zin.fp.seek(info.header_offset)
            h = zin.fp.read(30)
            n, m = struct.unpack('<HH', h[26:30])
            zin.fp.seek(info.header_offset + 30 + n + m)
            raw = zin.fp.read(info.compress_size)
        name = info.filename.encode('utf-8')
        offset = out.tell()
        extra = b''
        if info.compress_type == zipfile.ZIP_STORED:
            pad = (4 - (offset + 30 + len(name)) % 4) % 4
            extra = b'\0' * pad
        dostime = info.date_time
        t = (dostime[3] << 11) | (dostime[4] << 5) | (dostime[5] // 2)
        d = ((dostime[0] - 1980) << 9) | (dostime[1] << 5) | dostime[2]
        flags = 0x800 if info.flag_bits & 0x800 else 0
        body = data if data is not None else raw
        out.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, flags, info.compress_type, t, d,
                              info.CRC, len(body), info.file_size, len(name), len(extra)) + name + extra)
        out.write(body)
        central.append((info, name, offset, t, d, flags, len(body)))
    cd_start = out.tell()
    for info, name, offset, t, d, flags, csize in central:
        out.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, flags, info.compress_type, t, d,
                              info.CRC, csize, info.file_size, len(name), 0, 0, 0, 0, 0, offset) + name)
    cd_size = out.tell() - cd_start
    out.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central), cd_size, cd_start, 0))
