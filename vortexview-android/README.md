# VortexView for Android

A photo viewer for sorting, on your phone: the Android version of
[VortexView for Windows](../vortexview/README.md). Open a folder of photos,
choose the folders you want photos moved into, and **tap one**. The photo you're
looking at moves there and the next one slides into its place. **Undo** puts it
back.

Set up to nine destination folders (*Keepers*, *Rejects*, *Print*…) and sort a
whole camera roll with one thumb.

## Install

1. On your phone, open
   **https://github.com/JasonHaddad88/demo-repo/releases/tag/vortexview-android-latest**
2. Download **VortexView.apk** and open it.
3. Android asks to allow installs from your browser or file manager. Allow it
   for this one install.

Every push rebuilds the APK, and newer builds install over older ones and keep
your folders. Android 8.0 or newer. It installs alongside VortexPlorer; they
are separate apps.

## Using it

1. **Open a folder of photos.** Android's folder picker opens: find the folder
   and tap **Use this folder**, then **Allow**. Camera photos are usually in
   *DCIM › Camera*. SD cards and USB drives are in the picker's menu (☰).
2. **Choose the folder to move photos into.** It appears as a button at the
   bottom. Add more with **+ Folder**.
3. Look at each photo and **tap a folder button** to move it there, or **swipe**
   to leave it and go to the next one.

| Gesture | Does |
| --- | --- |
| Tap a folder button | Move the photo there; the next one takes its place |
| **Undo** (top) or *Undo* on the message | Put the last moved photo back, as many times as you like |
| Swipe left / right | Next / previous photo |
| Pinch, or double-tap | Zoom in where your fingers are; double-tap again to fit |
| Drag (when zoomed) | Look around the photo |
| Back (when zoomed) | Back to the whole photo |
| Tap the photo | Hide or show the controls (full screen) |
| Press and hold a folder button | Change that folder, or remove it from the list |
| ⋮ menu | Open another folder, or read the folder again |

## What it's careful about

- **Never overwrites.** If the destination already has `IMG_0042.jpg`, the
  moved photo becomes `IMG_0042 (2).jpg`, and the message says so.
- **Undo really undoes.** It moves the file back and shows it again, even after
  you've opened another folder. If something new has taken its old name, it
  comes back as `name (2)` rather than replacing it.
- **A real move when possible.** Within the same storage the photo is moved
  instantly and keeps its date. Between internal storage and an SD card, Android
  can't move, so the photo is copied, the copy's size is checked, and only then
  is the original removed. If anything goes wrong the original stays where it was.
- **Zooming shows the real pixels.** Photos open at screen size for speed. Zoom
  in past that and the part on screen is read at full resolution, so even a
  huge photo zooms in sharp without running out of memory. Past 300%
  the pixels are shown as crisp squares, so you can check focus.
- **Phone photos are the right way up** (EXIF orientation), and the size shown
  is the size you see.
- **Quick:** the photos either side are prepared while you look, so a swipe is
  instant.
- **No permissions.** VortexView sees only the folders you choose in Android's
  own folder picker, and nothing else on your phone.
- **It remembers** your folders and the photo you were on: next time it opens
  right where you stopped.
- Only photos directly in the folder are listed, in name order (`IMG_2` before
  `IMG_10`). Videos, other files, subfolders and Android's hidden
  `.trashed-…` files are left alone.

Formats: JPEG, PNG, WebP, GIF (first frame), BMP, and HEIC/HEIF (Android 9 and
newer) and AVIF (Android 12 and newer). A photo the phone can't show can still
be moved.

## Development

```
cd vortexview-android
./gradlew :core:test            # anywhere with Java 17
./gradlew :app:assembleDebug    # needs the Android SDK
```

`:core` is plain Kotlin: the folder and position, moving, never-overwrite
names, undo, the zoom and pan arithmetic, and mapping a zoomed region onto
sideways-stored camera pixels. Its tests run on any JVM, with real files.
`:app` is the Android app (Jetpack Compose); it moves files through the Storage
Access Framework (`app/.../data/SafStorage.kt`). The GitHub workflow
`.github/workflows/vortexview-android.yml` runs the tests, builds the APK and
publishes it on every push.

The signing key `app/vortexview.jks` is committed on purpose, so every build
installs as an update of the last. It isn't a secret: anyone can sign an APK
with it. Use your own key before handing the app to other people.
