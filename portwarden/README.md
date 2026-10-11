# portwarden

See every port your own machine is listening on, trace each one back to the
service that owns it, and start, stop, enable or disable that service — from a
CLI or a local web dashboard.

No third-party packages. Python 3.9+ and the standard library only.

```
$ portwarden scan --ports 1-1024,3000-3010,5432
PORT  PROTO  ADDRESS    EXPOSURE        PROBE  PROCESS        SERVICE             RUN  BOOT  FLAGS
----  -----  ---------  --------------  -----  -------------  ------------------  ---  ----  ---------
22    tcp    0.0.0.0    all-interfaces  open   sshd (912)     ssh.service         yes  yes   protected
80    tcp    0.0.0.0    all-interfaces  open   nginx (4242)   nginx.service       yes  yes
3000  tcp    127.0.0.1  loopback        open   node (8810)    -                   -    -
5432  tcp    127.0.0.1  loopback        open   postgres (771) postgresql.service  yes  no

$ portwarden stop nginx
stop nginx? [y/N] y
[ok] stop nginx: ok
        now: running=no at-boot=yes

$ portwarden disable 5432 --by-port --yes
[ok] disable postgresql.service: ok
        now: running=yes at-boot=no
```

## Install

```bash
git clone https://github.com/JasonHaddad88/Vortex-Portwarden.git
cd Vortex-Portwarden
pip install -e .          # gives you the `portwarden` command anywhere
```

Or run it straight out of the checkout, with nothing installed:

```bash
python3 -m portwarden ports     # Linux, macOS
py -m portwarden ports          # Windows (`python3` is not a Windows command)
```

## What it does

**Discovery** reads the kernel's socket table — `ss` on Linux (falling back to
`/proc/net/*` parsed directly), `netstat -ano` on Windows, `lsof` on macOS —
so every listening TCP and UDP socket is reported with the pid behind it.
`scan` additionally makes a real TCP connection to each requested port on
127.0.0.1, which proves a port is actually accepting connections even when the
socket table could not be read (another user's process, no root).

**Attribution** maps each pid to a service: the cgroup of the process for
systemd, `tasklist /svc` on Windows, `launchctl list` on macOS.

**Control** runs the service manager for you:

| Action | systemd | Windows | launchd |
| --- | --- | --- | --- |
| start | `systemctl start U` | `sc start S` | `launchctl kickstart D/L` |
| stop | `systemctl stop U` | `sc stop S` | `launchctl bootout D/L` |
| restart | `systemctl restart U` | `Restart-Service S` | `launchctl kickstart -k D/L` |
| enable (at boot) | `systemctl enable U` | `sc config S start= auto` | `launchctl enable D/L` |
| disable (at boot) | `systemctl disable U` | `sc config S start= disabled` | `launchctl disable D/L` |

"Running" and "at boot" are separate everywhere: disabling a service does not
stop it, and stopping one does not keep it down across a reboot. Both columns
are always shown so you can see which you changed.

## Commands

```
portwarden ports                     # listening sockets from the socket table
portwarden scan [--ports SPEC]       # the same, plus a TCP connect scan of 127.0.0.1
portwarden port 8080                 # what holds a single port
portwarden services [--all] [--filter X]
portwarden status nginx
portwarden start|stop|restart|enable|disable TARGET [--by-port] [--force] [-y]
portwarden audit [--limit N]         # every change portwarden has made
portwarden serve [--port 8787] [--open]
portwarden env                       # detected platform, backend, log path
```

Global flags: `--json` (machine-readable output), `--dry-run` (print the exact
command, change nothing), `--scope user|system` (user units on systemd and
launchd), `--sudo` / `--no-sudo`.

Port specs are comma-separated numbers and ranges: `--ports 22,80,8000-8100`.

## Web dashboard

```bash
portwarden serve --open
# portwarden dashboard  http://127.0.0.1:8787/?token=3Qk9…
```

Three tabs: **Ports** (every listener, with per-row start/stop/enable/disable),
**Services** (everything the service manager knows about, filterable) and
**History** (the audit log). Destructive actions ask for confirmation first and
report the exact command that ran.

The dashboard is deliberately boxed in, because this process can stop services:

* it binds a loopback address and refuses anything routable;
* every `/api/*` call must carry the access token printed at startup — the UI
  takes it from the URL, keeps it in `sessionStorage` for that tab only, and
  strips it from the address bar;
* the `Host` header must be loopback, which blocks DNS-rebinding attacks from a
  web page you happen to have open;
* a cross-origin `Origin` header is rejected outright;
* the page loads no external scripts, fonts or styles (enforced by CSP).

## Safety model

* **Protected services.** Units that keep you logged in, networked or firewalled
  (`sshd`, `systemd-*`, `NetworkManager`, `dbus`, `RpcSs`, `com.apple.*` and
  friends — see `portwarden/safety.py`) are refused for stop, restart and
  disable. Pass `--force`, or tick *allow protected* in the dashboard, to
  override. Start and enable are never blocked.
* **Confirmation.** Stop, restart and disable prompt on the CLI and open a modal
  in the dashboard. `--yes` skips the prompt; non-interactive use without
  `--yes` is refused rather than guessed at.
* **Dry run.** `--dry-run` prints the command it would run and touches nothing.
* **Audit log.** Every attempt — successful, failed or refused — is appended as
  a JSON line to `~/.local/state/portwarden/audit.log`
  (`%LOCALAPPDATA%\portwarden\audit.log` on Windows, `$PORTWARDEN_AUDIT_LOG` to
  override). `portwarden audit` reads it back.
* **No shells.** Commands are executed as argv lists, never through a shell, and
  service names are validated against a strict pattern first, so a name like
  `nginx; reboot` or `--now` is rejected before it reaches the service manager.
* **Localhost only.** Scanning refuses non-loopback hosts; this is a tool for
  your own machine, not a network scanner.

## Privileges

Reading ports needs no privileges, but pids belonging to other users only show
up when you are root (Linux/macOS) or elevated (Windows). Changing a
system service does need privileges:

* **Linux/macOS** — portwarden prefixes privileged commands with `sudo -n` when
  you are not root, so configure sudo for the commands you need or run it under
  `sudo`. `--no-sudo` turns that off; `--scope user` manages your own user units
  without any of it.
* **Windows** — run it from an elevated console; `sc` reports *Access denied*
  otherwise. portwarden surfaces that message with the hint attached.

## Limitations

* A port with no service behind it (`-` in the SERVICE column, *unmanaged* in
  the dashboard) is a process started outside the service manager — a container,
  a dev server, a shell job. portwarden will not kill processes; stop those where
  you started them.
* Docker-published ports belong to the daemon, so they map to `docker.service`
  rather than the container.
* Containers and WSL distributions without systemd as PID 1 have no service
  manager: ports are listed, nothing can be switched, and the tool says so.
* UDP is listed but not probed — a UDP connect scan cannot distinguish silence
  from a closed port.

## Development

```bash
python3 -m unittest discover -s tests -t .   # 137 tests, no network, no root
```

The test suite fakes every external command through `portwarden.runner.FakeRunner`
and covers the three socket-table parsers, the `/proc/net` fallback, all three
service backends' command construction, the protection rules, the HTTP API's
auth and rebinding defences, and the CLI's exit codes. The only real sockets it
opens are on 127.0.0.1 with an ephemeral port.
