"""The VortexView icon — a photo with an arrow carrying it off — drawn in
code, so the window and the .exe share it without a binary in the repo.

    python -m vortexview.icon VortexView.ico
"""

from __future__ import annotations

import sys

from PIL import Image, ImageDraw

VIOLET = (91, 61, 245, 255)
WHITE = (255, 255, 255, 255)
SKY = (196, 186, 255, 255)


def draw(size: int = 256) -> Image.Image:
    """Drawn large and scaled down, so small sizes stay crisp."""
    big = 512
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    pen = ImageDraw.Draw(image)
    pen.rounded_rectangle((16, 16, big - 16, big - 16), radius=96, fill=VIOLET)

    # The photo: a frame with a hill and a sun.
    frame = (88, 124, 336, 332)
    pen.rounded_rectangle(frame, radius=18, fill=SKY, outline=WHITE, width=18)
    pen.polygon([(106, 314), (178, 222), (232, 280), (270, 240), (320, 314)], fill=VIOLET)
    pen.ellipse((262, 160, 300, 198), fill=WHITE)

    # The arrow carrying it off to the right.
    pen.rectangle((300, 384, 400, 416), fill=WHITE)
    pen.polygon([(392, 352), (448, 400), (392, 448)], fill=WHITE)
    pen.line([(212, 364), (212, 400), (300, 400)], fill=WHITE, width=32, joint="curve")

    return image.resize((size, size), Image.LANCZOS)


def save_ico(path: str) -> None:
    draw(256).save(path, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


if __name__ == "__main__":
    save_ico(sys.argv[1] if len(sys.argv) > 1 else "VortexView.ico")
