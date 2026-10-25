# VortexView

A photo viewer for sorting. Open a folder of photos, point at the folder you
want photos moved into, and press **M** (or click the button): the photo you're
looking at moves there and the next one takes its place. **Ctrl+Z** puts it
back.

Set up to nine destination folders and sort into all of them with the number
keys — `1` Keepers, `2` Rejects, `3` Print… — without touching the mouse.

## Get it

**No Python needed:** download `VortexView.exe` from
https://github.com/JasonHaddad88/demo-repo/releases/tag/vortexview-latest
and run it. It isn't digitally signed, so Windows may say *"Windows protected
your PC"* the first time: click **More info → Run anyway**.

**With Python** (3.9 or newer), from the repository folder:

```
py -m pip install pillow
py -m vortexview
```

or double-click `vortexview\VortexView.pyw`. Add `py -m pip install pillow-heif`
to open iPhone HEIC photos (the .exe already includes it).

You can also pass a folder or a photo: `py -m vortexview "D:\Camera\2024"`, or
set VortexView as the *Open with* program for your photos.

## Using it

1. **Open folder…** (Ctrl+O) — the folder of photos to go through.
2. **Choose the folder to move photos into…** — your first destination.
   Add more with **+ Another folder…** (Ctrl+T).
3. Look at each photo and press **M** or **1** to move it to the first folder,
   **2**–**9** for the others, or **→** to leave it and go to the next one.

| Key | Does |
| --- | --- |
| `M` or `1` | Move the photo to the first folder |
| `2` … `9` | Move it to folder 2 … 9 |
| `Ctrl+Z` | Undo the last move (as many times as you like) |
| `→` `←` / mouse wheel / `Space` `Backspace` | Next / previous photo |
| `Page Up` `Page Down` | Jump 10 photos |
| `Home` `End` | First / last photo |
| `F11` or double-click | Full screen (`Esc` to leave) |
| `F5` | Re-read the folder |
| `Ctrl+O` / `Ctrl+T` | Open a folder / add a destination folder |

Right-click a destination button to change it, open it in Explorer, or remove
it from the list (removing never touches the folder itself).

## What it's careful about

- **Never overwrites.** If the destination already has `IMG_0042.jpg`, the
  moved photo becomes `IMG_0042 (2).jpg`, and the status bar says so.
- **Undo really undoes.** Ctrl+Z moves the file back to where it was and shows
  it again — even after you've opened a different folder. If something new has
  taken its old name, it comes back as `name (2)` rather than replacing it.
- **Phone photos are the right way up** (EXIF orientation), and the size shown
  is the size you see.
- **Big photos are quick:** JPEGs are decoded straight at screen size, and the
  next and previous photos are prepared in the background while you look.
- **Small photos aren't blown up** past their real size.
- **Locked files are explained**, not silently skipped: a photo open in another
  program stays put and the status bar tells you why.
- **It remembers** your destination folders, window size, and the folder and
  photo you were on — next time it opens right where you stopped.
- Moves across drives work (copied, then removed from the original drive).
- Only the photos directly in the folder are listed, in Explorer's order
  (`IMG_2` before `IMG_10`); other files and subfolders are left alone.

Formats: JPEG, PNG, GIF (first frame), BMP, WebP, TIFF, ICO — plus HEIC, HEIF
and AVIF with `pillow-heif`.

Settings live in `%APPDATA%\VortexView\settings.json`.

## Development

```
python -m unittest tests.test_vortexview_library tests.test_vortexview_app
```

`test_vortexview_library` covers scanning, natural order, moving, name
collisions, undo and settings without any GUI. `test_vortexview_app` opens the
real window, presses real keys and checks files really moved; on Linux run it
under `xvfb-run`. The GitHub workflow `.github/workflows/vortexview.yml` runs
both on Windows and builds `VortexView.exe` with PyInstaller on every push.
