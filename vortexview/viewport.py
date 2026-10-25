"""Zoom and pan arithmetic, kept apart from the window so it can be tested.

Coordinates come in two kinds: *image* coordinates are pixels of the original
photo (upright), *view* coordinates are pixels of the canvas. The photo is
always drawn so that view = view_centre + (image - centre) * scale.
"""

from __future__ import annotations


class Viewport:
    MAX_ZOOM = 8.0  # 800%: far enough to see single pixels on any photo
    STEP = 1.25

    def __init__(self, image_w: int, image_h: int, view_w: int, view_h: int) -> None:
        self.image_w = max(1, image_w)
        self.image_h = max(1, image_h)
        self.view_w = max(1, view_w)
        self.view_h = max(1, view_h)
        self.zoom: float | None = None  # None means "fit to window"
        self.cx = self.image_w / 2
        self.cy = self.image_h / 2

    # -- scale --------------------------------------------------------------

    @property
    def fit(self) -> float:
        """The scale that shows the whole photo, never enlarging a small one."""
        return min(self.view_w / self.image_w, self.view_h / self.image_h, 1.0)

    @property
    def scale(self) -> float:
        return self.fit if self.zoom is None else self.zoom

    @property
    def max_zoom(self) -> float:
        return max(self.MAX_ZOOM, self.fit)

    @property
    def zoomed(self) -> bool:
        return self.zoom is not None

    @property
    def percent(self) -> int:
        return round(self.scale * 100)

    # -- changing it --------------------------------------------------------

    def reset(self) -> None:
        self.zoom = None
        self.cx = self.image_w / 2
        self.cy = self.image_h / 2

    def set_zoom(self, value: float, anchor: tuple[float, float] | None = None) -> None:
        """Zooms so the image point under [anchor] (view coords) stays put."""
        value = min(max(value, self.fit), self.max_zoom)
        if value <= self.fit * 1.0001:
            self.reset()
            return
        ax, ay = anchor if anchor is not None else (self.view_w / 2, self.view_h / 2)
        px, py = self.image_point(ax, ay)
        self.zoom = value
        self.cx = px - (ax - self.view_w / 2) / value
        self.cy = py - (ay - self.view_h / 2) / value
        self._clamp()

    def zoom_by(self, steps: float, anchor: tuple[float, float] | None = None) -> None:
        self.set_zoom(self.scale * self.STEP ** steps, anchor)

    def toggle_actual_size(self, anchor: tuple[float, float] | None = None) -> None:
        """Fit ↔ 100%; a photo already shown at 100% goes to 200% instead."""
        if self.zoomed:
            self.reset()
        else:
            self.set_zoom(1.0 if self.fit < 0.999 else 2.0, anchor)

    def pan(self, dx: float, dy: float) -> None:
        """Drags the photo by (dx, dy) view pixels."""
        if not self.zoomed:
            return
        self.cx -= dx / self.scale
        self.cy -= dy / self.scale
        self._clamp()

    def resize(self, view_w: int, view_h: int) -> None:
        self.view_w = max(1, view_w)
        self.view_h = max(1, view_h)
        if self.zoom is not None and self.zoom <= self.fit * 1.0001:
            self.reset()
        else:
            self._clamp()

    # -- reading it ---------------------------------------------------------

    def image_point(self, vx: float, vy: float) -> tuple[float, float]:
        s = self.scale
        return self.cx + (vx - self.view_w / 2) / s, self.cy + (vy - self.view_h / 2) / s

    def layout(self) -> tuple[tuple[float, float, float, float], tuple[int, int], tuple[int, int]]:
        """What to draw: the visible region of the photo (image coords), the
        size to draw it at, and where its top-left corner goes on the canvas."""
        s = self.scale
        half_w = self.view_w / (2 * s)
        half_h = self.view_h / (2 * s)
        x0 = max(0.0, self.cx - half_w)
        y0 = max(0.0, self.cy - half_h)
        x1 = min(float(self.image_w), self.cx + half_w)
        y1 = min(float(self.image_h), self.cy + half_h)
        size = (max(1, round((x1 - x0) * s)), max(1, round((y1 - y0) * s)))
        origin = (round(self.view_w / 2 + (x0 - self.cx) * s), round(self.view_h / 2 + (y0 - self.cy) * s))
        return (x0, y0, x1, y1), size, origin

    def _clamp(self) -> None:
        """Keeps the photo filling the view where it can, centred where it can't."""
        s = self.scale
        half_w = self.view_w / (2 * s)
        half_h = self.view_h / (2 * s)
        if self.image_w <= 2 * half_w:
            self.cx = self.image_w / 2
        else:
            self.cx = min(max(self.cx, half_w), self.image_w - half_w)
        if self.image_h <= 2 * half_h:
            self.cy = self.image_h / 2
        else:
            self.cy = min(max(self.cy, half_h), self.image_h - half_h)
