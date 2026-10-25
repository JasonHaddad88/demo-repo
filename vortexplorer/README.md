# VortexPlorer

An Android file explorer built around one idea: **looking at your files in a
random order, done properly.** Browse your phone, an SD card, a USB drive, a
Windows share or a WebDAV server — then shuffle a folder, a selection, or
several places at once, and step through them or run a slideshow.

It also reads and writes: copy and move between any of those places, and write
a shuffle back out as numbered files so a car stereo, TV or photo frame that
plays in name order plays *your* random order.

## Install

1. On your phone, open
   **https://github.com/JasonHaddad88/demo-repo/releases/tag/vortexplorer-latest**
   (this link keeps working if the repository is renamed — GitHub redirects it).
2. Download **VortexPlorer.apk** and open it.
3. Android asks to allow installs from your browser or file manager — allow it
   for this one install.

Every push rebuilds the APK, and newer builds install over older ones without
losing your places or settings. Android 8.0 or newer.

## Places

| Place | How to add it | Read | Write |
| --- | --- | --- | --- |
| **Photos & videos** | Always there; asks for permission the first time | ✓ | — |
| **Folder, SD card or USB drive** | *Add a place → Folder, SD card or USB drive*, then pick it in the system picker | ✓ | ✓ |
| **Windows / SMB share** | *Add a place → Windows / SMB share* | ✓ | ✓ |
| **WebDAV server** | *Add a place → WebDAV server* | ✓ | ✓ |

**USB drives**: plug the drive in with an OTG adapter, then add it as a
folder — it shows up in the picker's side menu, usually under its volume name.
Pick the whole drive or one folder on it.

**Windows shares**: on the PC, right-click a folder → *Properties → Sharing →
Share*. In VortexPlorer enter the PC's name or IP address, the share name, and
your Windows user name and password. *Test* connects for real before you save.
SMB 2 and 3 are supported (every Windows since Vista, every current NAS);
SMB 1-only devices are not.

**WebDAV**: Nextcloud and ownCloud (`https://host/remote.php/dav/files/USER/`),
most NAS boxes, `rclone serve webdav`, and IIS WebDAV publishing. Prefer
`https://`; plain `http://` works on a home network but sends the password
unencrypted, and the app says so.

Passwords are encrypted with a key held in the Android Keystore that never
leaves the phone.

## Shuffling

Shuffle from anywhere:

- the **shuffle button** in a folder — that folder, with or without subfolders;
- a **selection** — long-press to select files and folders, then shuffle;
- a **whole place** — its ⋮ menu → *Shuffle all of it*;
- **several places together** — tick them on the home screen. One pool, one
  order: phone photos, a USB stick and a NAS share mixed as one.

### Modes

| Mode | What it does |
| --- | --- |
| **Pure shuffle** | Every order equally likely. Verified statistically, not assumed. |
| **Unseen first** | Files you haven't opened yet come first, shuffled; then the rest. |
| **Spread folders** *(default)* | Mixes folders evenly through the whole order. A folder with 60% of the files fills about 60% of any stretch, and the back-to-back repeats it can't avoid are scattered rather than piled up at the end. |
| **Lean newer** | Newer files tend to come up earlier; old ones still appear. Strength 1–12×. |
| **Rediscover** | The reverse: older files tend to come first. Good for forgotten photos. |

Plus an optional **never the same folder twice in a row**, which breaks up
repeats wherever another folder can fill the gap.

### Seeds

Every shuffle has a short seed such as `K7QM2XWD`. The same seed, mode and
files give **the same order on any phone**: tap the seed to copy it, and enter
it later (*⋮ → Change order or seed*) to get back an order you liked — or send
it to someone with the same files. *Unseen first* depends on what you've
viewed, so it replays exactly only until you view more.

### Slideshow and resuming

*Play slideshow* advances on a timer (2–30 s), keeps the screen on, and hides
everything but the picture. At the end it reshuffles with a new seed and keeps
going, never opening the new round with the file you just saw. Swipe any time;
the timer restarts.

*Pick up where you left off* on the home screen rebuilds your last shuffle from
its seed and opens it at the file you were on.

## Writing

- **Copy / Move to…** any writable place: phone to NAS, share to USB stick,
  WebDAV to phone. Folders come with their contents. Name clashes get
  `name (2).jpg`, and a failed copy never leaves a half-written file behind.
- **Delete, rename, new folder** wherever the place allows it.
- **Export as numbered files** (from a shuffle's ⋮ menu): copies the shuffle
  as `001 - beach.jpg`, `002 - song.mp3`… into a new folder, plus an `.m3u`
  playlist when there's music or video. Names unsafe for a FAT32 stick
  (`: ? * " < > |`) are cleaned on the way.
- **Renumber files in random order** (a folder's ⋮ menu): renames the files
  already in a folder with shuffled numbers — reshuffle a USB stick without
  copying anything. Run it again for a new order; old numbers are replaced, not
  stacked, and names like `2024 holiday.jpg` keep their own digits.

## Viewing

Photos are decoded at screen size, turned the right way up (EXIF), and cached,
so scrolling back through a network share doesn't fetch every photo again.
Double-tap to zoom, then drag or pinch. Videos and music open in the phone's
own player — files on a share are fetched first.

## Permissions and privacy

- **Photos and videos** (or *Storage* on Android 12 and older): only to show
  the gallery. On Android 14 you can allow just selected photos.
- **Network**: for SMB and WebDAV.
- Folders and drives need no permission at all: the system picker grants access
  to exactly the folder you choose, and removing the place gives it back.
- What you've viewed (for *Unseen first*) is stored on the phone as anonymous
  64-bit fingerprints, not file names. *Settings → Forget everything I've seen*
  clears it.
- Nothing is sent anywhere except to the shares and servers you add.

## Limits worth knowing

- The gallery is read-only: changing it would need the system to ask you about
  every single file.
- Animated GIFs show their first frame; HEIC photos need Android 9 or newer.
- Long copies run while the app is open. Leave it in the foreground for big
  transfers — Android may stop background work.
- Some phones mount NTFS-formatted USB drives read-only; that is Android's
  limit, not VortexPlorer's. FAT32 and exFAT drives are writable.
- **The signing key is in this repository on purpose**, so every build — yours
  or CI's — installs as an update to the last. That is fine for your own phone.
  Before distributing the app to other people, generate your own key and keep
  it secret.

## How it's built

```
vortexplorer/
  core/   plain Kotlin: shuffle engine, seeds, SMB, WebDAV, copier, exporter
  app/    Android (Jetpack Compose): gallery, folders/USB, UI, viewer
```

All the logic that matters lives in `core`, which builds and tests on any JVM:

```bash
cd vortexplorer
./gradlew :core:test
```

120 tests, including chi-square checks that *Pure shuffle* is uniform, a pinned
check of the random generator against an independent reference implementation,
and one contract suite run against the local filesystem, a real Samba server
and a real WebDAV server. Point the suite at your own servers with
`VORTEX_SMB_HOST`/`PORT`/`SHARE`/`USER`/`PASSWORD` and
`VORTEX_WEBDAV_URL`/`USER`/`PASSWORD`; without them those two suites skip.

`app` needs the Android SDK (Android Studio, or `ANDROID_HOME` set); without
one, the build simply leaves it out. The GitHub workflow
`.github/workflows/vortexplorer.yml` runs the tests and builds the APK on every
push.
