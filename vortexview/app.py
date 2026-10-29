"""The VortexView window.

Look at a photo, press a key or click a button, and it moves into the folder
you pointed at; the next photo slides into its place. Ctrl+Z puts it back.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tkinter as tk
from collections import OrderedDict
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from tkinter import filedialog, messagebox

from PIL import Image, ImageOps, ImageTk

from . import __version__, theme
from .library import MAX_TARGETS, Gallery, MoveError, Settings, heif_supported, same_folder
from .viewport import Viewport

UI_FONT = "Segoe UI"
MONO_FONT = "Consolas"
# Zooming in loads the photo at full size, up to this many pixels a side
# (a 12000 x 9000 photo still fits comfortably in memory).
FULL_SIDE = 12000


def _register_heif() -> None:
    if heif_supported():
        from pillow_heif import register_heif_opener

        register_heif_opener()


class Picture:
    """A decoded photo, already shrunk to at most screen size."""

    def __init__(self, image: Image.Image | None, original_size: tuple[int, int], error: str = "") -> None:
        self.image = image
        self.original_size = original_size
        self.error = error


def decode(path: Path, max_side: int) -> Picture:
    try:
        with Image.open(path) as source:
            original = source.size
            # Report the size as seen: a portrait phone photo is stored sideways.
            if source.getexif().get(0x0112) in (5, 6, 7, 8):
                original = (original[1], original[0])
            # JPEGs can be decoded straight at a smaller size: much faster for big photos.
            source.draft("RGB", (max_side, max_side))
            source.load()
            turned = ImageOps.exif_transpose(source)  # phones store rotation as a tag
            has_alpha = turned.mode in ("RGBA", "LA") or (turned.mode == "P" and "transparency" in turned.info)
            image = turned.convert("RGBA" if has_alpha else "RGB")
        image.thumbnail((max_side, max_side), Image.LANCZOS)
        return Picture(image, original)
    except Exception as exc:  # noqa: BLE001 - any unreadable file is shown, not fatal
        return Picture(None, (0, 0), f"Can't show {path.name}: {exc}")


class Loader:
    """Decodes photos on worker threads and keeps the last few ready."""

    def __init__(self, max_side: int, keep: int = 8) -> None:
        self.max_side = max_side
        self.keep = keep
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="vortexview-decode")
        self.pending: OrderedDict[Path, Future] = OrderedDict()

    def request(self, path: Path) -> Future:
        future = self.pending.get(path)
        if future is None:
            future = self.pool.submit(decode, path, self.max_side)
            self.pending[path] = future
        self.pending.move_to_end(path)
        while len(self.pending) > self.keep:
            _, old = self.pending.popitem(last=False)
            old.cancel()
        return future

    def forget(self, path: Path) -> None:
        self.pending.pop(path, None)

    def full_size(self, path: Path) -> Future:
        """The photo at its own resolution, for zooming in; not kept."""
        return self.pool.submit(decode, path, FULL_SIDE)

    def close(self) -> None:
        # Wait for a decode in flight: Tk objects must never be freed from a
        # worker thread, and a running decode can trigger garbage collection.
        self.pool.shutdown(wait=True, cancel_futures=True)


def spaced(text: str, gap: str = "\u200a") -> str:
    """Capitals with a hair space between letters: Tk can't letter-space, and
    Tawla's labels and buttons are all wide-spaced capitals."""
    return gap.join(text.upper())


class VButton(tk.Frame):
    """Tawla's `.btn` in Tk: transparent, outlined, wide-spaced capitals. On
    hover (or [lit], for the one that matters most) it fills with a wash of
    its colour and its border goes solid. The frame is the border."""

    def __init__(
        self,
        parent: tk.Misc,
        p: theme.Palette,
        text: str,
        command,
        kind: str = "secondary",
        lit: bool = False,
        big: bool = False,
        on: str | None = None,
    ) -> None:
        super().__init__(parent, padx=1, pady=1, highlightthickness=0)
        self.p = p
        self.command = command
        self.lit = lit
        self._text = text
        self.ink, self.wash = {
            "primary": (p.accent, p.accent_wash),
            "danger": (p.danger, p.danger_wash),
        }.get(kind, (p.accent2, p.accent2_wash))
        self.rest = p.danger_wash if kind == "danger" else p.border_strong
        self.ground = on or p.glass
        self.label = tk.Label(
            self,
            text=spaced(text),
            font=(UI_FONT, 10 if big else 9, "bold" if big else "normal"),
            padx=16 if big else 11,
            pady=8 if big else 4,
            cursor="hand2",
        )
        self.label.pack(fill=tk.BOTH, expand=True)
        self.hover = False
        for widget in (self, self.label):
            widget.bind("<Enter>", lambda _e: self._set_hover(True))
            widget.bind("<Leave>", lambda _e: self._set_hover(False))
            widget.bind("<ButtonRelease-1>", self._release)
        self._paint()

    def _set_hover(self, hover: bool) -> None:
        self.hover = hover
        self._paint()

    def _paint(self) -> None:
        on = self.hover or self.lit
        super().configure(background=self.ink if on else self.rest)
        self.label.configure(background=self.wash if on else self.ground, foreground=self.ink)

    def _release(self, event: tk.Event) -> None:
        # A click counts only if the pointer is still over the button when let go.
        widget = event.widget
        if 0 <= event.x < widget.winfo_width() and 0 <= event.y < widget.winfo_height():
            self.invoke()

    def invoke(self) -> None:
        if self.command is not None:
            self.command()

    def set_lit(self, lit: bool) -> None:
        self.lit = lit
        self._paint()

    def cget(self, key: str):
        return self._text if key == "text" else super().cget(key)

    def configure(self, cnf=None, **kw):
        if "text" in kw:
            self._text = kw.pop("text")
            self.label.configure(text=spaced(self._text))
        if cnf or kw:
            return super().configure(cnf, **kw)
        return None

    config = configure


class App:
    def __init__(self, root: tk.Tk, settings_file: Path | None = None) -> None:
        self.root = root
        self.settings_file = settings_file
        self.settings = Settings.load(settings_file)
        self.p = theme.palette(self.settings.theme)
        self.gallery = Gallery()
        self.gallery.shuffle_seed = self.settings.shuffle_seed
        side = max(root.winfo_screenwidth(), root.winfo_screenheight(), 1024)
        self.loader = Loader(min(side, 4096))
        self.photo: ImageTk.PhotoImage | None = None
        self.picture: Picture | None = None
        self.shown_path: Path | None = None
        self.resize_job: str | None = None
        self.poll_job: str | None = None
        # Zoom: the view of the photo on screen, and its full-size copy once loaded.
        self.view: Viewport | None = None
        self.full: Image.Image | None = None
        self.full_future: Future | None = None
        self.full_job: str | None = None
        self.drag_from: tuple[int, int] | None = None
        # The haloed ground behind the photo, kept until the size or theme changes.
        self.ground: ImageTk.PhotoImage | None = None
        self.ground_key: tuple | None = None
        self.brand: list[ImageTk.PhotoImage] = []
        self.welcome_art: list[ImageTk.PhotoImage] = []

        root.title("VortexView")
        root.minsize(640, 420)
        try:
            from .icon import draw

            self.icon: ImageTk.PhotoImage | None = ImageTk.PhotoImage(draw(64))
            root.iconphoto(True, self.icon)
        except (tk.TclError, OSError):
            self.icon = None
        if self.settings.geometry:
            try:
                root.geometry(self.settings.geometry)
            except tk.TclError:
                root.geometry("1100x760")
        else:
            root.geometry("1100x760")

        self._build()
        self._bind_keys()
        root.protocol("WM_DELETE_WINDOW", self.close)

    # -- layout -------------------------------------------------------------

    def _build(self) -> None:
        p = self.p
        self.root.configure(background=p.bg)
        self.brand = [ImageTk.PhotoImage(theme.logo(28, p)), ImageTk.PhotoImage(theme.wordmark(p, 22))]

        # The glass bar along the top, as Tawla's: the brand on the left, the controls on the right.
        self.top = tk.Frame(self.root, background=p.glass, padx=14, pady=9)
        self.top.pack(side=tk.TOP, fill=tk.X)
        self.top_line = tk.Frame(self.root, background=p.border, height=1)
        self.top_line.pack(side=tk.TOP, fill=tk.X)
        top = self.top
        tk.Label(top, image=self.brand[0], background=p.glass).pack(side=tk.LEFT)
        tk.Label(top, image=self.brand[1], background=p.glass).pack(side=tk.LEFT, padx=(10, 18))
        VButton(top, p, "Open folder", self.choose_folder).pack(side=tk.LEFT)
        VButton(top, p, "◀", lambda: self.step(-1)).pack(side=tk.LEFT, padx=(10, 3))
        VButton(top, p, "▶", lambda: self.step(1)).pack(side=tk.LEFT, padx=(3, 12))
        # Right to left, as pack places them.
        self.theme_button = VButton(top, p, p.name, self.choose_theme)
        self.theme_button.pack(side=tk.RIGHT)
        VButton(top, p, "⛶", self.toggle_fullscreen).pack(side=tk.RIGHT, padx=(0, 8))
        VButton(top, p, "Undo", self.undo).pack(side=tk.RIGHT, padx=(0, 8))
        VButton(top, p, "+", lambda: self.zoom_by(1)).pack(side=tk.RIGHT, padx=(0, 14))
        self.zoom_button = VButton(top, p, "Fit", self.zoom_fit)
        self.zoom_button.pack(side=tk.RIGHT, padx=3)
        VButton(top, p, "−", lambda: self.zoom_by(-1)).pack(side=tk.RIGHT, padx=(14, 0))
        self.name_order_button = VButton(top, p, "Name order", self.sort_by_name)
        self.shuffle_button = VButton(top, p, "Shuffle", self.shuffle, kind="primary")
        self.shuffle_button.pack(side=tk.RIGHT)

        # Last, so the buttons have their room first and the name takes what is left.
        info = tk.Frame(top, background=p.glass)
        info.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.name_label = tk.Label(info, text="", background=p.glass, foreground=p.text, font=(UI_FONT, 10, "bold"), anchor="w")
        self.name_label.pack(side=tk.TOP, fill=tk.X)
        self.position_label = tk.Label(info, text="", background=p.glass, foreground=p.accent2, font=(MONO_FONT, 9), anchor="w")
        self.position_label.pack(side=tk.TOP, fill=tk.X)

        self.status = tk.Label(
            self.root, text="", anchor="w", padx=16, pady=6,
            background=p.glass, foreground=p.subtle, font=(UI_FONT, 9),
        )
        self.status.pack(side=tk.BOTTOM, fill=tk.X)
        self.status_colour = p.subtle

        self.target_bar = tk.Frame(self.root, background=p.glass, padx=16, pady=10)
        self.target_bar.pack(side=tk.BOTTOM, fill=tk.X)
        self.target_line = tk.Frame(self.root, background=p.border, height=1)
        self.target_line.pack(side=tk.BOTTOM, fill=tk.X)

        self.canvas = tk.Canvas(self.root, background=p.bg, highlightthickness=0)
        self.canvas.pack(side=tk.TOP, fill=tk.BOTH, expand=True)
        self.canvas.bind("<Configure>", self._on_resize)
        self.canvas.bind("<Double-Button-1>", lambda e: self.zoom_toggle((e.x, e.y)))
        self.canvas.bind("<ButtonPress-1>", self._drag_start)
        self.canvas.bind("<B1-Motion>", self._drag)
        self.canvas.bind("<ButtonRelease-1>", self._drag_end)

        self.rebuild_targets()
        self._show_order()

    def _section_head(self, parent: tk.Misc, text: str) -> tk.Label:
        """Tawla's `.section-head h2`: small, wide-spaced capitals in the muted ink."""
        return tk.Label(
            parent, text=spaced(text, "\u2009"), background=self.p.glass, foreground=self.p.muted,
            font=(UI_FONT, 9, "bold"),
        )

    def rebuild_targets(self) -> None:
        p = self.p
        for child in self.target_bar.winfo_children():
            child.destroy()
        self.target_buttons: list[VButton] = []

        if not self.settings.targets:
            self._section_head(self.target_bar, "Where should photos go?").pack(side=tk.LEFT, padx=(0, 14))
            VButton(
                self.target_bar, p, "Choose the folder to move photos into…", self.add_target,
                kind="primary", lit=True, big=True,
            ).pack(side=tk.LEFT)
            tk.Label(
                self.target_bar, text="  then press M (or 1) to move the photo you're on",
                background=p.glass, foreground=p.muted, font=(UI_FONT, 9),
            ).pack(side=tk.LEFT)
            return

        self._section_head(self.target_bar, "Move this photo to").pack(side=tk.LEFT, padx=(0, 14))
        for slot, folder in enumerate(self.settings.targets):
            path = Path(folder)
            missing = not path.is_dir()
            label = f"{slot + 1}   {path.name or folder}" + ("  (missing)" if missing else "")
            button = VButton(
                self.target_bar, p, label, lambda s=slot: self.move_to(s),
                kind="danger" if missing else ("primary" if slot == 0 else "secondary"),
                lit=slot == 0 and not missing, big=True,
            )
            button.pack(side=tk.LEFT, padx=(0, 8))
            for widget in (button, button.label):
                widget.bind("<Button-3>", lambda event, s=slot: self._target_menu(event, s))
            self.target_buttons.append(button)
        if len(self.settings.targets) < MAX_TARGETS:
            VButton(self.target_bar, p, "+ Another folder…", self.add_target).pack(side=tk.LEFT, padx=(6, 0))
        tk.Label(
            self.target_bar, text="Right-click a folder to change or remove it",
            background=p.glass, foreground=p.muted, font=(UI_FONT, 9),
        ).pack(side=tk.RIGHT)

    def _menu(self) -> tk.Menu:
        """A menu in the theme's colours (as far as the system lets a menu be coloured)."""
        p = self.p
        return tk.Menu(
            self.root, tearoff=False, background=p.surface2, foreground=p.text,
            activebackground=p.accent_wash, activeforeground=p.accent2, borderwidth=1, relief="solid",
        )

    def _target_menu(self, event: tk.Event, slot: int) -> None:
        menu = self._menu()
        menu.add_command(label="Move photo here", command=lambda: self.move_to(slot))
        menu.add_command(label="Change folder…", command=lambda: self.change_target(slot))
        menu.add_command(label="Open in Explorer", command=lambda: self.reveal(Path(self.settings.targets[slot])))
        menu.add_separator()
        menu.add_command(label="Remove from the list", command=lambda: self.remove_target(slot))
        try:
            menu.tk_popup(event.x_root, event.y_root)
        finally:
            menu.grab_release()

    # -- themes and order -----------------------------------------------------

    def choose_theme(self) -> None:
        """Tawla's theme select, as a menu under the button."""
        menu = self._menu()
        choice = tk.StringVar(value=self.p.id)
        for tid in theme.THEME_IDS:
            menu.add_radiobutton(
                label=theme.palette(tid).name, value=tid, variable=choice,
                command=lambda t=tid: self.apply_theme(t),
            )
        button = self.theme_button
        try:
            menu.tk_popup(button.winfo_rootx(), button.winfo_rooty() + button.winfo_height())
        finally:
            menu.grab_release()

    def apply_theme(self, theme_id: str) -> None:
        """Repaints the whole window in another theme, and remembers it."""
        self.p = theme.palette(theme_id)
        self.settings.theme = self.p.id
        self.settings.save(self.settings_file)
        status = (self.status.cget("text"), self.status.cget("foreground") == self.p.danger)
        for widget in (self.top, self.top_line, self.status, self.target_bar, self.target_line, self.canvas):
            widget.destroy()
        self.ground = None
        self.ground_key = None
        self._build()
        self.say(status[0], error=status[1])
        self.root.update_idletasks()
        if self.picture is not None and self.picture.image is not None:
            self._render()
        else:
            self.show()

    def shuffle(self) -> None:
        """A new random order for the photos, from the top. Files are never renamed."""
        if len(self.gallery.images) < 2:
            self.say("Nothing to shuffle")
            return
        self.settings.shuffle_seed = self.gallery.shuffle()
        self.settings.save(self.settings_file)
        self.say(f"Shuffled {len(self.gallery.images)} photos   ·   S for a new order, N for name order")
        self.show()

    def sort_by_name(self) -> None:
        self.gallery.sort_by_name()
        self.settings.shuffle_seed = None
        self.settings.save(self.settings_file)
        self.say("Back to name order")
        self.show()

    def _show_order(self) -> None:
        """Shuffle lights up while shuffled, and Name order appears beside it."""
        shuffled = self.gallery.shuffle_seed is not None
        self.shuffle_button.set_lit(shuffled)
        if shuffled and self.name_order_button.winfo_manager() != "pack":
            # Packed after Shuffle, so it sits to its left.
            self.name_order_button.pack(side=tk.RIGHT, padx=(0, 8), after=self.shuffle_button)
        elif not shuffled:
            self.name_order_button.pack_forget()

    def _bind_keys(self) -> None:
        bind = self.root.bind
        bind("<Left>", lambda _e: self.step(-1))
        bind("<Right>", lambda _e: self.step(1))
        bind("<Prior>", lambda _e: self.step(-10))
        bind("<Next>", lambda _e: self.step(10))
        bind("<Home>", lambda _e: self.jump(0))
        bind("<End>", lambda _e: self.jump(len(self.gallery.images) - 1))
        bind("<space>", lambda _e: self.step(1))
        bind("<BackSpace>", lambda _e: self.step(-1))
        bind("<KeyPress-m>", lambda _e: self.move_to(0))
        bind("<KeyPress-M>", lambda _e: self.move_to(0))
        for slot in range(MAX_TARGETS):
            bind(f"<KeyPress-{slot + 1}>", lambda _e, s=slot: self.move_to(s))
            bind(f"<KeyPress-KP_{slot + 1}>", lambda _e, s=slot: self.move_to(s))
        bind("<KeyPress-s>", lambda _e: self.shuffle())
        bind("<KeyPress-S>", lambda _e: self.shuffle())
        bind("<KeyPress-n>", lambda _e: self.sort_by_name())
        bind("<KeyPress-N>", lambda _e: self.sort_by_name())
        bind("<Control-z>", lambda _e: self.undo())
        bind("<Control-Z>", lambda _e: self.undo())
        bind("<Control-o>", lambda _e: self.choose_folder())
        bind("<Control-t>", lambda _e: self.add_target())
        bind("<F5>", lambda _e: self.reload())
        bind("<F11>", lambda _e: self.toggle_fullscreen())
        bind("<Escape>", lambda _e: self.leave_fullscreen())
        bind("<MouseWheel>", lambda e: self.step(-1 if e.delta > 0 else 1))
        bind("<Button-4>", lambda _e: self.step(-1))
        bind("<Button-5>", lambda _e: self.step(1))
        # Zoom. Ctrl+1 and Ctrl+0 are more specific than plain 1, so they never move a photo.
        for key in ("<KeyPress-plus>", "<KeyPress-equal>", "<KP_Add>", "<Control-plus>", "<Control-equal>"):
            bind(key, lambda _e: self.zoom_by(1))
        for key in ("<KeyPress-minus>", "<KP_Subtract>", "<Control-minus>"):
            bind(key, lambda _e: self.zoom_by(-1))
        bind("<Control-Key-0>", lambda _e: self.zoom_fit())
        bind("<Control-Key-1>", lambda _e: self.zoom_actual())
        # Ctrl+wheel (and a touchpad pinch, which Windows sends as Ctrl+wheel) zooms at the pointer.
        bind("<Control-MouseWheel>", lambda e: self.zoom_by(e.delta / 120, self._pointer()))
        bind("<Control-Button-4>", lambda _e: self.zoom_by(1, self._pointer()))
        bind("<Control-Button-5>", lambda _e: self.zoom_by(-1, self._pointer()))

    # -- opening ------------------------------------------------------------

    def start(self, path: str | None = None) -> None:
        """Opens what was asked for, or carries on where you left off."""
        if path:
            self.open_path(Path(path))
        elif self.settings.last_folder and Path(self.settings.last_folder).is_dir():
            last = Path(self.settings.last_image) if self.settings.last_image else None
            self.open_path(last if last is not None and last.is_file() else Path(self.settings.last_folder))
        else:
            self.show()

    def choose_folder(self) -> None:
        folder = filedialog.askdirectory(
            parent=self.root,
            title="Open a folder of photos",
            initialdir=self.settings.last_folder or str(Path.home()),
        )
        if folder:
            self.open_path(Path(folder))

    def open_path(self, path: Path) -> None:
        try:
            self.gallery.open(path)
        except MoveError as exc:
            self.say(str(exc), error=True)
            self.show()
            return
        self.settings.last_folder = str(self.gallery.folder)
        count = len(self.gallery.images)
        self.say(f"{count} photo{'s' if count != 1 else ''} in {self.gallery.folder}")
        self.show()

    def reload(self) -> None:
        try:
            self.gallery.reload()
        except MoveError as exc:
            self.say(str(exc), error=True)
        self.show()

    # -- targets ------------------------------------------------------------

    def add_target(self) -> int | None:
        folder = filedialog.askdirectory(
            parent=self.root,
            title="Choose the folder to move photos into",
            initialdir=self.settings.targets[-1] if self.settings.targets else (self.settings.last_folder or str(Path.home())),
        )
        if not folder:
            return None
        if self.gallery.folder is not None and same_folder(Path(folder), self.gallery.folder):
            self.say("That's the folder you're looking through — choose a different one to move photos into", error=True)
            return None
        try:
            slot = self.settings.add_target(Path(folder))
        except MoveError as exc:
            self.say(str(exc), error=True)
            return None
        self.settings.save(self.settings_file)
        self.rebuild_targets()
        self.say(f"Press {slot + 1}{' or M' if slot == 0 else ''} to move a photo into {Path(folder).name}")
        return slot

    def change_target(self, slot: int) -> None:
        folder = filedialog.askdirectory(parent=self.root, title="Choose a different folder", initialdir=self.settings.targets[slot])
        if folder:
            self.settings.targets[slot] = folder
            self.settings.save(self.settings_file)
            self.rebuild_targets()

    def remove_target(self, slot: int) -> None:
        removed = Path(self.settings.targets.pop(slot))
        self.settings.save(self.settings_file)
        self.rebuild_targets()
        self.say(f"Removed {removed.name} from the list (the folder itself is untouched)")

    # -- the point of it all ------------------------------------------------

    def move_to(self, slot: int) -> None:
        if self.gallery.current is None:
            self.say("Open a folder of photos first (Ctrl+O)", error=True)
            return
        if slot >= len(self.settings.targets):
            # The next free number (or M before any folder is set) offers to pick one now.
            if slot != len(self.settings.targets):
                self.say(f"There's no folder {slot + 1} — add folders with “+ Another folder…”", error=True)
                return
            if slot > 0 and not messagebox.askyesno(
                "VortexView", f"There's no folder {slot + 1} yet. Choose one now?", parent=self.root,
            ):
                return
            chosen = self.add_target()
            if chosen is None:
                return
            slot = chosen

        target = Path(self.settings.targets[slot])
        moving = self.gallery.current
        try:
            record = self.gallery.move_current(target)
        except MoveError as exc:
            self.say(str(exc), error=True)
            return
        if moving is not None:
            self.loader.forget(moving)
        note = f"  as {record.destination.name} (a file with that name was already there)" if record.renamed else ""
        left = len(self.gallery.images)
        self.say(f"Moved {moving.name} → {target.name}{note}   ·   {left} left   ·   Ctrl+Z to undo")
        self.show()

    def undo(self) -> None:
        try:
            record = self.gallery.undo()
        except MoveError as exc:
            self.say(str(exc), error=True)
            return
        if record is None:
            self.say("Nothing to undo")
            return
        self.say(f"Put {record.destination.name} back in {record.destination.parent.name}")
        self.show()

    # -- showing ------------------------------------------------------------

    def step(self, delta: int) -> None:
        if self.gallery.go(delta):
            self.show()

    def jump(self, position: int) -> None:
        if self.gallery.images:
            self.gallery.go_to(position)
            self.show()

    def show(self) -> None:
        current = self.gallery.current
        total = len(self.gallery.images)
        if current is None:
            self._forget_zoom()
            self.shown_path = None
            self.picture = None
            self.position_label.configure(text="")
            self.name_label.configure(text="")
            self.root.title("VortexView")
            self._show_order()
            if self.gallery.folder is None:
                self._message("Open a folder of photos\n\nCtrl+O, or the “Open folder” button", welcome=True)
            elif self.gallery.history:
                self._message(f"All done — no photos left in\n{self.gallery.folder}\n\nCtrl+Z puts the last one back")
            else:
                self._message(f"No photos in\n{self.gallery.folder}")
            return

        self.position_label.configure(text=self._position())
        self.name_label.configure(text=current.name)
        self._show_order()
        self.root.title(f"{current.name} ({self.gallery.index + 1} of {total}) — VortexView")
        self.settings.last_image = str(current)

        future = self.loader.request(current)
        # Warm up the neighbours so the next key press is instant.
        for offset in (1, -1, 2):
            neighbour = self.gallery.neighbour(offset)
            if neighbour is not None:
                self.loader.request(neighbour)

        if current != self.shown_path:
            self._forget_zoom()  # every photo starts fitted to the window
        self.shown_path = current
        if future.done():
            self._display(current, future.result())
        else:
            self.picture = None
            self._message("Loading…", small=True)
            self._wait_for(current, future)

    def _wait_for(self, path: Path, future: Future) -> None:
        if self.poll_job is not None:
            self.root.after_cancel(self.poll_job)
            self.poll_job = None

        def check() -> None:
            self.poll_job = None
            if self.shown_path != path:
                return  # the user has moved on
            if future.done():
                self._display(path, future.result())
            else:
                self.poll_job = self.root.after(15, check)

        self.poll_job = self.root.after(15, check)

    def _display(self, path: Path, picture: Picture) -> None:
        self.picture = picture
        if picture.image is None:
            self._message(picture.error, error=True)
        else:
            if self.view is None:
                width, height = picture.original_size if all(picture.original_size) else picture.image.size
                self.view = Viewport(width, height, self.canvas.winfo_width(), self.canvas.winfo_height())
            self._render()
        self._update_details(path, picture)

    def _update_details(self, path: Path, picture: Picture) -> None:
        try:
            size = path.stat().st_size
        except OSError:
            size = 0
        width, height = picture.original_size
        details = f"{width} × {height}   ·   " if width else ""
        self.position_label.configure(text=f"{self._position()}   ·   {details}{_human_size(size)}")

    def _position(self) -> str:
        """Mono and zero-padded, as Tawla prints its numbers: "007 / 120"."""
        total = len(self.gallery.images)
        text = f"{self.gallery.index + 1:0{len(str(total))}d} / {total}"
        return text + ("   ·   shuffled" if self.gallery.shuffle_seed is not None else "")

    def _render(self, quick: bool = False) -> None:
        picture, view = self.picture, self.view
        if picture is None or picture.image is None or view is None:
            return
        view.resize(self.canvas.winfo_width(), self.canvas.winfo_height())
        # The screen-sized copy is sharp enough (and quicker) until zoomed in past it.
        sharp_enough = view.scale <= picture.image.width / view.image_w * 1.01
        source = self.full if self.full is not None and not sharp_enough else picture.image
        box, size, origin = view.layout()
        # Source pixels per photo pixel: below 1 for the screen-sized copy.
        kx = source.width / view.image_w
        ky = source.height / view.image_h
        if not view.zoomed and size == source.size:
            shown = source
        else:
            # Only the visible part is resized, so deep zoom stays quick on huge photos.
            if view.scale / kx >= 3:
                resample = Image.NEAREST  # deep zoom: show crisp pixels, not a blur
            elif quick:
                resample = Image.BILINEAR  # while dragging, keep up with the mouse
            else:
                resample = Image.LANCZOS
            shown = source.resize(size, resample, box=(box[0] * kx, box[1] * ky, box[2] * kx, box[3] * ky))
        self.photo = ImageTk.PhotoImage(shown)
        self.canvas.delete("all")
        self._draw_ground()
        self.canvas.create_image(origin[0], origin[1], image=self.photo, anchor="nw")
        self.canvas.configure(cursor="fleur" if view.zoomed else "")
        self.zoom_button.configure(text=f"{view.percent}%" if view.zoomed else "Fit")
        if self.full is None and not sharp_enough and picture.image.width < view.image_w:
            self._load_full_size()

    # -- zoom ---------------------------------------------------------------

    def _zoomable(self) -> bool:
        return self.view is not None and self.picture is not None and self.picture.image is not None

    def zoom_by(self, steps: float, anchor: tuple[float, float] | None = None) -> None:
        if self._zoomable():
            self.view.zoom_by(steps, anchor)
            self._render()

    def zoom_fit(self) -> None:
        if self._zoomable():
            self.view.reset()
            self._render()

    def zoom_actual(self) -> None:
        if self._zoomable():
            self.view.set_zoom(1.0)
            self._render()

    def zoom_toggle(self, anchor: tuple[float, float] | None = None) -> None:
        if self._zoomable():
            self.view.toggle_actual_size(anchor)
            self._render()

    def _pointer(self) -> tuple[float, float] | None:
        """Where the mouse is on the photo area, or None if it's elsewhere."""
        x = self.canvas.winfo_pointerx() - self.canvas.winfo_rootx()
        y = self.canvas.winfo_pointery() - self.canvas.winfo_rooty()
        inside = 0 <= x < self.canvas.winfo_width() and 0 <= y < self.canvas.winfo_height()
        return (x, y) if inside else None

    def _drag_start(self, event: tk.Event) -> None:
        self.drag_from = (event.x, event.y)

    def _drag(self, event: tk.Event) -> None:
        if self.drag_from is None or not self._zoomable() or not self.view.zoomed:
            return
        self.view.pan(event.x - self.drag_from[0], event.y - self.drag_from[1])
        self.drag_from = (event.x, event.y)
        self._render(quick=True)

    def _drag_end(self, _event: tk.Event) -> None:
        if self.drag_from is not None and self._zoomable() and self.view.zoomed:
            self._render()  # the sharp version, now the mouse has stopped
        self.drag_from = None

    def _load_full_size(self) -> None:
        """Zoomed in past the screen-sized copy: decode the original in the background."""
        path = self.shown_path
        if self.full_future is not None or path is None:
            return
        future = self.loader.full_size(path)
        self.full_future = future

        def check() -> None:
            self.full_job = None
            if self.full_future is not future:
                return  # moved on to another photo
            if not future.done():
                self.full_job = self.root.after(30, check)
                return
            full = future.result()
            if full.image is not None:
                self.full = full.image
                self._render()

        self.full_job = self.root.after(30, check)

    def _forget_zoom(self) -> None:
        self.view = None
        self.full = None
        if self.full_future is not None:
            self.full_future.cancel()
            self.full_future = None
        if self.full_job is not None:
            self.root.after_cancel(self.full_job)
            self.full_job = None
        self.canvas.configure(cursor="")
        self.zoom_button.configure(text="Fit")

    def _draw_ground(self) -> None:
        """The theme's haloed (and on GTX and Bamboo, textured) ground, behind everything."""
        width = max(self.canvas.winfo_width(), 1)
        height = max(self.canvas.winfo_height(), 1)
        key = (width, height, self.p.id)
        if self.ground_key != key:
            self.ground = ImageTk.PhotoImage(theme.ground(width, height, self.p))
            self.ground_key = key
        self.canvas.create_image(0, 0, image=self.ground, anchor="nw", tags=("ground",))

    def _message(self, text: str, error: bool = False, small: bool = False, welcome: bool = False) -> None:
        p = self.p
        self.canvas.delete("all")
        self.photo = None
        self._draw_ground()
        width = max(self.canvas.winfo_width(), 1)
        height = max(self.canvas.winfo_height(), 1)
        y = height // 2
        if welcome:
            # The brand, large, above the way in.
            self.welcome_art = [ImageTk.PhotoImage(theme.logo(72, p)), ImageTk.PhotoImage(theme.wordmark(p, 40))]
            self.canvas.create_image(width // 2, y - 120, image=self.welcome_art[0])
            self.canvas.create_image(width // 2, y - 50, image=self.welcome_art[1])
            y += 20
        self.canvas.create_text(
            width // 2,
            y,
            text=text,
            fill=p.danger if error else (p.muted if small else p.text),
            font=(UI_FONT, 11 if small else 14),
            justify="center",
            width=max(width - 80, 200),
        )

    def _on_resize(self, _event: tk.Event) -> None:
        if self.resize_job is not None:
            self.root.after_cancel(self.resize_job)
        self.resize_job = self.root.after(60, self._after_resize)

    def _after_resize(self) -> None:
        self.resize_job = None
        if self.picture is not None and self.picture.image is not None:
            self._render()
        else:
            self.show()

    # -- odds and ends ------------------------------------------------------

    def say(self, text: str, error: bool = False) -> None:
        self.status.configure(text=text, foreground=self.p.danger if error else self.status_colour)

    def toggle_fullscreen(self) -> None:
        self.root.attributes("-fullscreen", not bool(self.root.attributes("-fullscreen")))

    def leave_fullscreen(self) -> None:
        self.root.attributes("-fullscreen", False)

    def reveal(self, folder: Path) -> None:
        try:
            if sys.platform == "win32":
                os.startfile(folder)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(folder)])
            else:
                subprocess.Popen(["xdg-open", str(folder)])
        except OSError as exc:
            self.say(f"Couldn't open {folder}: {exc}", error=True)

    def close(self) -> None:
        if not bool(self.root.attributes("-fullscreen")):
            self.settings.geometry = self.root.geometry()
        self.settings.save(self.settings_file)
        if self.poll_job is not None:
            self.root.after_cancel(self.poll_job)
        self._forget_zoom()
        self.loader.close()
        self.photo = None
        self.icon = None
        self.ground = None
        self.brand = []
        self.welcome_art = []
        self.root.destroy()


def _human_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return ""


def _sharp_on_high_dpi() -> None:
    """Without this, Windows blurs the whole window on scaled displays."""
    if sys.platform != "win32":
        return
    try:
        import ctypes

        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:  # noqa: BLE001 - older Windows, or no shcore
        pass


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if args and args[0] in ("-h", "--help"):
        print("VortexView — open a folder of photos and move each one into a folder you choose.\n")
        print("usage: vortexview [FOLDER or PHOTO]")
        return 0
    if args and args[0] in ("-V", "--version"):
        print(f"VortexView {__version__}")
        return 0
    _sharp_on_high_dpi()
    _register_heif()
    root = tk.Tk()
    app = App(root)
    root.after(50, lambda: app.start(args[0] if args else None))
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
