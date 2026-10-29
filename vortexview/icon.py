"""The VortexView icon: the Vortex family's mark, as VortexTawla draws it (a
purple-to-cyan square with a dark core), with a photo in the core. Drawn in
code, so the window and the .exe share it without a binary in the repo.

    python -m vortexview.icon VortexView.ico
"""

from __future__ import annotations

import sys

from PIL import Image, ImageDraw

PURPLE = (0xA8, 0x55, 0xF7, 255)
CYAN = (0x67, 0xE8, 0xF9, 255)
CORE = (0x06, 0x06, 0x0A, 255)


def draw(size: int = 256) -> Image.Image:
    """Drawn large and scaled down, so small sizes stay crisp."""
    big = 512
    # 135° gradient, purple at the top left to cyan at the bottom right.
    ramp = Image.linear_gradient("L").resize((big, big))
    diagonal = Image.blend(ramp, ramp.rotate(90), 0.5)
    gradient = Image.composite(Image.new("RGBA", (big, big), CYAN), Image.new("RGBA", (big, big), PURPLE), diagonal)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=round(big * 0.22), fill=255)
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    image.paste(gradient, (0, 0), mask)

    # The core, inset as on Tawla's icon, and the photo in it: a hill and a sun.
    pen = ImageDraw.Draw(image)
    inset = round(big * 0.17)
    pen.rounded_rectangle((inset, inset, big - 1 - inset, big - 1 - inset), radius=round(big * 0.11), fill=CORE)
    pen.polygon([(130, 372), (210, 262), (268, 326), (306, 288), (392, 372)], fill=PURPLE)
    pen.ellipse((316, 140, 364, 188), fill=CYAN)

    return image.resize((size, size), Image.LANCZOS)


def save_ico(path: str) -> None:
    draw(256).save(path, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


if __name__ == "__main__":
    save_ico(sys.argv[1] if len(sys.argv) > 1 else "VortexView.ico")
