#!/usr/bin/env python3
"""Adds a SEEKTABLE (a point every N seconds) to a FLAC file written by ffmpeg, which writes none (`flac` and most rippers
do). The table takes the place of part of ffmpeg's PADDING block, so no audio byte moves.

    flac_seektable.py <file.flac> [seconds]
"""
import struct
import sys

BLOCK_SIZES = {1: 192, 2: 576, 3: 1152, 4: 2304, 5: 4608, 8: 256, 9: 512, 10: 1024, 11: 2048, 12: 4096, 13: 8192,
               14: 16384, 15: 32768}


def crc8(data):
    c = 0
    for b in data:
        c ^= b
        for _ in range(8):
            c = ((c << 1) ^ 0x07) & 0xff if c & 0x80 else (c << 1) & 0xff
    return c


def frame_at(buf, i):
    """(first sample, block size) of a valid fixed-blocksize frame header at buf[i], else None."""
    if buf[i] != 0xff or buf[i + 1] != 0xf8:
        return None
    bs_code, sr_code = buf[i + 2] >> 4, buf[i + 2] & 0x0f
    if bs_code == 0 or sr_code == 15 or (buf[i + 3] >> 4) > 10:
        return None
    j = i + 4
    first = buf[j]
    n = 0 if first < 0x80 else 1 if first >= 0xc0 and first < 0xe0 else 2 if first < 0xf0 else 3 if first < 0xf8 else 4 if first < 0xfc else 5 if first < 0xfe else -1
    if n < 0 or (first >= 0x80 and first < 0xc0):
        return None
    num = first & (0x7f >> n if n else 0x7f)
    for k in range(n):
        b = buf[j + 1 + k]
        if b & 0xc0 != 0x80:
            return None
        num = (num << 6) | (b & 0x3f)
    j += 1 + n
    if bs_code == 6:
        size = buf[j] + 1; j += 1
    elif bs_code == 7:
        size = (buf[j] << 8 | buf[j + 1]) + 1; j += 2
    else:
        size = BLOCK_SIZES.get(bs_code)
        if size is None:
            return None
    j += {12: 1, 13: 2, 14: 2}.get(sr_code, 0)
    if crc8(buf[i:j]) != buf[j]:
        return None
    return num * size, size


def main():
    path = sys.argv[1]
    every = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
    buf = bytearray(open(path, "rb").read())
    assert buf[:4] == b"fLaC", "not a FLAC file"
    pos, blocks = 4, []
    while True:
        last, kind, length = buf[pos] & 0x80, buf[pos] & 0x7f, int.from_bytes(buf[pos + 1:pos + 4], "big")
        blocks.append((pos, kind, length))
        pos += 4 + length
        if last:
            break
    audio = pos
    rate = int.from_bytes(buf[8 + 10:8 + 13], "big") >> 4
    assert all(kind != 3 for _, kind, _ in blocks), "already has a SEEKTABLE"
    pad = [b for b in blocks if b[1] == 1]
    assert pad, "no PADDING block to put the table in"
    pad_pos, _, pad_len = pad[0]

    frames, i = [], audio
    while True:
        i = buf.find(b"\xff\xf8", i, len(buf) - 16)
        if i < 0:
            break
        f = frame_at(buf, i)
        if f and (not frames or f[0] > frames[-1][0]):
            frames.append((f[0], i - audio, f[1]))
            i += 16
        else:
            i += 1
    points, next_at = [], 0
    for sample, offset, size in frames:
        if sample >= next_at:
            points.append(struct.pack(">QQH", sample, offset, size))
            next_at = (len(points)) * every * rate
    table = b"".join(points)
    if len(table) + 4 > pad_len:
        sys.exit(f"{len(points)} points do not fit in {pad_len} bytes of padding")
    is_last = buf[pad_pos] & 0x80
    rest = pad_len - len(table) - 4
    new = bytes([3]) + len(table).to_bytes(3, "big") + table + bytes([1 | is_last]) + rest.to_bytes(3, "big") + bytes(rest)
    buf[pad_pos:pad_pos + 4 + pad_len] = new
    open(path, "wb").write(buf)
    print(f"{path}: SEEKTABLE with {len(points)} points every {every:g} s ({len(frames)} frames)")


main()
