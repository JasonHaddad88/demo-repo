# Read this file for good luck.

Good luck! Wait, you think luck comes this easily?

# CAUTION
This file has been tampered with.

## This code has been placed from my PC. See the difference? Me neither.
Lorem ipsum

# NOW FOR SOME...INSTRUCTIONS!

# Step 1. Drive carefully
The road to Hell is paved with good intentions
---

# portwarden

This repository also contains **portwarden**, a zero-dependency Python app that
lists every port this machine is listening on, traces each one to the service
that owns it, and starts, stops, enables or disables those services — from a CLI
or a localhost-only web dashboard.

```bash
python3 -m portwarden ports        # what is listening right now
python3 -m portwarden scan         # the same, plus a TCP connect scan
python3 -m portwarden serve --open # web dashboard on 127.0.0.1:8787
```

On Windows the launcher is `py`, not `python3`: `py -m portwarden scan`.

Full documentation: [`portwarden/README.md`](portwarden/README.md).

---

# Vortex VXpress

Also in this repository: **VXpress**, which downloads the video behind a link,
or turns any video — a link or a file already on this PC — into an `.mp3`. It
drives yt-dlp and ffmpeg; VXpress itself needs no Python packages.

```bash
python3 -m vxpress doctor                      # are yt-dlp and ffmpeg installed?
python3 -m vxpress mp3 "https://example.com/watch?v=abc"
python3 -m vxpress mp3 "~/Videos/lecture.mkv" --start 1:30
python3 -m vxpress get "https://example.com/watch?v=abc" --max-height 1080
python3 -m vxpress serve --open                # drag-and-drop web interface
```

On Windows use `py -m vxpress ...`.

Full documentation: [`vxpress/README.md`](vxpress/README.md).

---

# VortexPlorer (Android)

Also in this repository: **VortexPlorer**, an Android file explorer built
around shuffling. Browse your phone, an SD card, a USB drive, a Windows share
or a WebDAV server; shuffle a folder, a selection, or several places at once
with replayable seeds; copy and move between them; and export a shuffle as
numbered files for players that go by file name.

Install it on your phone from the
[`vortexplorer-latest` release](https://github.com/JasonHaddad88/demo-repo/releases/tag/vortexplorer-latest),
which every push rebuilds.

Full documentation: [`vortexplorer/README.md`](vortexplorer/README.md).

---

# VortexView (Windows)

Also in this repository: **VortexView**, a photo viewer for sorting. Open a
folder of photos, point at the folder you want them moved into, and press **M**:
the photo you're on moves there and the next one appears. Up to nine
destination folders on the number keys, and Ctrl+Z to undo.

Download `VortexView.exe` from the
[`vortexview-latest` release](https://github.com/JasonHaddad88/demo-repo/releases/tag/vortexview-latest),
or run it with Python: `py -m vortexview`.

Full documentation: [`vortexview/README.md`](vortexview/README.md).

# VortexView (Android)

The same photo sorter on your phone. Open a folder of photos, choose the
folders to move them into, and tap one: the photo you're on moves there and the
next one slides in. Swipe between photos, pinch or double-tap to zoom, shuffle
the order with one tap, and Undo puts a photo back.

On your phone, download `VortexView.apk` from the
[`vortexview-android-latest` release](https://github.com/JasonHaddad88/demo-repo/releases/tag/vortexview-android-latest).

Full documentation: [`vortexview-android/README.md`](vortexview-android/README.md).
