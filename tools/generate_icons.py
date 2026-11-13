#!/usr/bin/env python3
"""Generates the GramHub launcher icon rasters (no third-party deps).

Renders a Telegram-blue rounded square with a white paper plane and writes
density-specific PNGs for legacy (pre-API 26) launchers plus the adaptive-icon
foreground layers.
"""

import math
import os
import struct
import zlib

RES = "app/src/main/res"

# 24x24 Material "send" polygon: a paper plane with a notch on the trailing edge.
PLANE = [
    (2.01, 21.0),
    (23.0, 12.0),
    (2.01, 3.0),
    (2.0, 10.0),
    (17.0, 12.0),
    (2.0, 14.0),
]

BLUE_TOP = (0x2A, 0xAB, 0xEE)
BLUE_BOTTOM = (0x1C, 0x8D, 0xD6)

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

LEGACY_DP = 48
FOREGROUND_DP = 108
SS = 4  # supersampling factor per axis


def write_png(path, width, height, pixels):
    """pixels: flat list of (r, g, b, a) tuples, row-major."""

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type: none
        for x in range(width):
            raw.extend(pixels[y * width + x])

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)


def point_in_polygon(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def plane_polygon(size, scale, offset_y=0.0):
    """Centres the 24x24 plane polygon inside a `size` square."""

    unit = size * scale / 24.0
    ox = (size - 24 * unit) / 2.0
    oy = (size - 24 * unit) / 2.0 + offset_y * size
    return [(ox + px * unit, oy + py * unit) for px, py in PLANE]


def render(size, mask, plane_scale, plane_offset_y=0.0):
    """Supersampled render. `mask(u, v)` decides background coverage; pass None
    for a transparent background (adaptive-icon foreground layer)."""

    hi = size * SS
    poly = plane_polygon(hi, plane_scale, plane_offset_y)
    acc = [[[0, 0, 0, 0] for _ in range(size)] for _ in range(size)]

    for hy in range(hi):
        for hx in range(hi):
            sx, sy = int(hx / SS), int(hy / SS)
            u, v = (hx + 0.5) / hi, (hy + 0.5) / hi
            r = g = b = a = 0
            if mask is not None and mask(u, v):
                r = int(BLUE_TOP[0] + (BLUE_BOTTOM[0] - BLUE_TOP[0]) * v)
                g = int(BLUE_TOP[1] + (BLUE_BOTTOM[1] - BLUE_TOP[1]) * v)
                b = int(BLUE_TOP[2] + (BLUE_BOTTOM[2] - BLUE_TOP[2]) * v)
                a = 255
            if point_in_polygon(hx + 0.5, hy + 0.5, poly):
                r, g, b, a = 255, 255, 255, 255
            cell = acc[sy][sx]
            cell[0] += r
            cell[1] += g
            cell[2] += b
            cell[3] += a

    samples = SS * SS
    out = []
    for row in acc:
        for cell in row:
            out.append(tuple(min(255, val // samples) for val in cell))
    return out


def rounded_square(radius_ratio):
    def mask(u, v):
        r = radius_ratio
        cx = min(max(u, r), 1 - r)
        cy = min(max(v, r), 1 - r)
        return math.hypot(u - cx, v - cy) <= r + 1e-9

    return mask


def circle():
    return lambda u, v: math.hypot(u - 0.5, v - 0.5) <= 0.5


def full_bleed():
    return lambda u, v: True


def main():
    for density, scale in DENSITIES.items():
        legacy = int(round(LEGACY_DP * scale))
        fg = int(round(FOREGROUND_DP * scale))
        out_dir = os.path.join(RES, f"mipmap-{density}")

        write_png(
            os.path.join(out_dir, "ic_launcher.png"),
            legacy,
            legacy,
            render(legacy, rounded_square(0.22), 0.52),
        )
        write_png(
            os.path.join(out_dir, "ic_launcher_round.png"),
            legacy,
            legacy,
            render(legacy, circle(), 0.48),
        )
        # Adaptive-icon foreground: transparent, plane inside the 66dp safe zone.
        write_png(
            os.path.join(out_dir, "ic_launcher_foreground.png"),
            fg,
            fg,
            render(fg, None, 0.38),
        )
        print(f"{density}: legacy={legacy}px foreground={fg}px")


if __name__ == "__main__":
    main()
