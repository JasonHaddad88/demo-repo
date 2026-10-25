# Vortex VXpress

Download the video behind a link, or turn any video — a link or a file already
on your PC — into an `.mp3`. A CLI and a local web interface you can drop files
onto.

VXpress itself is pure standard library. It drives two programs you install
once: **yt-dlp** for links and **ffmpeg** for audio.

```
> vxpress mp3 "https://example.com/watch?v=abc123"
 / [####################....]  83.4%  downloading  Some Video
[ok]     C:\Users\You\VXpress\audio\Some Video [abc123].mp3

> vxpress mp3 "C:\Users\You\Videos\lecture.mkv" --bitrate 128 --start 1:30
[ok]     C:\Users\You\VXpress\audio\lecture.mp3

> vxpress get "https://example.com/watch?v=abc123" --max-height 1080
[ok]     C:\Users\You\VXpress\video\Some Video [abc123].mp4
```

## Install

```bash
git clone https://github.com/JasonHaddad88/Vortex-Portwarden.git
cd Vortex-Portwarden
pip install -e .            # gives you the `vxpress` command
```

Or run it out of the checkout with no install:

```bash
python3 -m vxpress doctor   # Linux, macOS
py -m vxpress doctor        # Windows
```

### The two programs it needs

`vxpress doctor` tells you what is present and how to install what is not:

```
TOOL     STATE  VERSION         FOUND VIA
-------  -----  --------------  ---------
yt-dlp   found  2026.10.21      PATH
ffmpeg   found  6.1.1           PATH
ffprobe  found  6.1.1           PATH
```

| | Windows | macOS | Linux |
| --- | --- | --- | --- |
| yt-dlp | `winget install yt-dlp.yt-dlp` | `brew install yt-dlp` | `python3 -m pip install -U yt-dlp` |
| ffmpeg | `winget install Gyan.FFmpeg` | `brew install ffmpeg` | `sudo apt install ffmpeg` |

Already have them somewhere odd? Point VXpress straight at them with
`VXPRESS_YTDLP`, `VXPRESS_FFMPEG` and `VXPRESS_FFPROBE`. A pip-installed yt-dlp
with no console script on PATH is found automatically (`python -m yt_dlp`).

Neither program is bundled, and neither is required for the other half to work:
with only ffmpeg you can still convert local files; with only yt-dlp you can
still download videos.

## Commands

```
vxpress mp3 TARGET...      convert a link or a local file to .mp3
vxpress get URL...         download the video itself
vxpress info TARGET        what a link or file contains, before you commit
vxpress doctor             check yt-dlp and ffmpeg
vxpress history            what VXpress has produced before
vxpress serve              the web interface
```

`TARGET` is an `http(s)` link or a path to a file on this computer; VXpress
works out which. Several targets in one run are fine, and `--batch-file list.txt`
reads one per line.

**Audio options** (`mp3`, and `get` accepts them too): `--bitrate 320`,
`--vbr 0` (variable bitrate, 0 best … 9 smallest), `--start 1:30`, `--end 2:45`,
`--thumbnail` (cover art, links only), `--no-metadata`.

**Video options** (`get`): `--max-height 1080`, `--container mp4|mkv|webm|any`.

**Everywhere**: `--out DIR`, `--json`, `--workers N`, `--playlist`.

Timestamps accept `90`, `1:30` or `0:01:30.5`.

Finished files land in `~/VXpress/audio` and `~/VXpress/video`
(`%USERPROFILE%\VXpress\...` on Windows) unless `--out` or `VXPRESS_OUTPUT_DIR`
says otherwise. Nothing is ever overwritten — a second `song.mp3` becomes
`song (2).mp3`.

## Web interface

```bash
vxpress serve --open
# Vortex VXpress  http://127.0.0.1:8799/?token=3Qk9xR2mN7vB...
```

Paste a link or a path, pick **.mp3** or **video**, press Start, and watch the
queue. Finished jobs get a **Save file…** button that hands the file to your
browser. **Drop a video file anywhere on the page** and it is sent to the local
server (a browser never reveals a file's real path), parked in a temp folder and
queued for conversion.

The same posture as the rest of these tools, because this process writes files
and runs other programs:

* the socket binds loopback and refuses anything routable;
* every `/api/*` call needs the token printed at startup — the page takes it from
  the URL, keeps it in `sessionStorage` for that tab, and strips it from the
  address bar;
* the `Host` header must be loopback, which blocks DNS-rebinding from any page
  you happen to have open;
* a cross-origin `Origin` header is rejected;
* the page loads no external scripts, styles or fonts (enforced by CSP);
* the download endpoint only serves a file that a finished job actually wrote,
  and only from inside that job's output folder.

## How it behaves

* **Progress is real**, parsed from yt-dlp's `--newline` output and ffmpeg's
  `-progress` stream, not a guess.
* **Jobs can be cancelled.** Cancel stops the child process; a queued job that
  has not started is simply dropped.
* **Two jobs run at once** by default (`--workers`).
* **Every finished job is logged** as a JSON line in
  `~/.local/state/vxpress/history.jsonl`
  (`%LOCALAPPDATA%\vxpress\history.jsonl` on Windows, `$VXPRESS_HISTORY` to
  override). `vxpress history` reads it back.
* **No shells.** Every command is built as an argv list, and the link is always
  passed after `--`, so a URL can never be read as an option.
* **Your own yt-dlp flags** (cookies, proxy, rate limits) go in
  `VXPRESS_YTDLP_ARGS`; VXpress otherwise runs yt-dlp with `--ignore-config` so
  it behaves the same on every machine.

## Limits worth knowing

* VXpress asks a site only for what it serves to an ordinary visitor. It does
  not sign in, and it cannot open DRM-protected streams (Netflix, Spotify and
  the like) — those fail with a plain message rather than a workaround.
* Whether you may keep a copy of something is not a question the tool can
  answer. Download what you have the right to download.
* mp3 is lossy and so is nearly every source: converting re-encodes once more.
  For music, 192 kbps or `--vbr 2` is a sensible floor.
* A file with no audio track (a screen recording with the mic off, say) is
  reported as such instead of producing a silent file.
* Cover art embedding needs the site to publish a thumbnail; local files have
  none to embed.
* Playlists are capped by nothing but your patience — `--playlist` with
  `--workers 4` is the fast path, and failures inside a playlist are skipped
  rather than aborting the rest.

## Development

```bash
python3 -m unittest discover -s tests -t .
```

The VXpress tests fake yt-dlp and ffmpeg through `tests/vxhelpers.py`, so the
suite needs no network, no root and neither program — except for a handful of
real-ffmpeg tests that generate their own clip with `lavfi` and skip when ffmpeg
is absent. Covered: link/path classification, filename safety on every OS,
timestamp parsing, tool discovery (PATH, env override, `python -m yt_dlp`),
ffprobe and ffmpeg command construction, both progress parsers, error-message
translation, the job queue (success, failure, cancellation, history), the CLI's
exit codes, and the HTTP API's auth, rebinding defences, upload and download
endpoints.
