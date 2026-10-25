"""Subprocess helpers: capture a short command, or stream a long one.

Everything is executed as an argv list, never through a shell.
"""

from __future__ import annotations

import subprocess
import threading
from collections import deque
from dataclasses import dataclass
from typing import Callable, Deque, Sequence

LINE_HISTORY = 200


@dataclass(frozen=True)
class Completed:
    argv: tuple[str, ...]
    returncode: int
    output: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0

    @property
    def last_line(self) -> str:
        lines = [line for line in self.output.splitlines() if line.strip()]
        return lines[-1] if lines else ""


class ToolMissing(RuntimeError):
    """Raised when an external program is not installed."""


class Cancelled(RuntimeError):
    """Raised when a job was cancelled while a process was running."""


def capture(argv: Sequence[str], timeout: float = 60.0) -> Completed:
    """Run a short command and collect all of its output."""
    argv = [str(a) for a in argv]
    try:
        proc = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            stdin=subprocess.DEVNULL,
        )
    except FileNotFoundError as exc:
        raise ToolMissing(f"{argv[0]}: not found") from exc
    except subprocess.TimeoutExpired:
        return Completed(tuple(argv), 124, f"{argv[0]}: timed out after {timeout}s")
    return Completed(tuple(argv), proc.returncode, (proc.stdout or "") + (proc.stderr or ""))


class Stream:
    """A running child process whose output is read line by line.

    ``on_line`` is called for every line of merged stdout/stderr, which is how
    progress is tracked. ``cancel()`` is safe to call from another thread.
    """

    def __init__(
        self,
        argv: Sequence[str],
        on_line: Callable[[str], None] | None = None,
        line_history: int = LINE_HISTORY,
    ) -> None:
        self.argv = tuple(str(a) for a in argv)
        self.on_line = on_line
        self.lines: Deque[str] = deque(maxlen=line_history)
        self._proc: subprocess.Popen | None = None
        self._lock = threading.Lock()
        self._cancelled = False

    @property
    def cancelled(self) -> bool:
        return self._cancelled

    def cancel(self, grace: float = 5.0) -> None:
        """Ask the child to stop, then insist."""
        with self._lock:
            self._cancelled = True
            proc = self._proc
        if proc is None or proc.poll() is not None:
            return
        try:
            proc.terminate()
        except OSError:
            return
        try:
            proc.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            try:
                proc.kill()
            except OSError:
                pass

    def run(self) -> Completed:
        try:
            # Text mode translates \r to \n, so tools that redraw a progress
            # line are read line by line just like any other output.
            proc = subprocess.Popen(  # noqa: S603 - argv list, no shell
                list(self.argv),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                text=True,
                bufsize=1,
                universal_newlines=True,
            )
        except FileNotFoundError as exc:
            raise ToolMissing(f"{self.argv[0]}: not found") from exc

        with self._lock:
            self._proc = proc
            if self._cancelled:  # cancelled between construction and start
                already_cancelled = True
            else:
                already_cancelled = False
        if already_cancelled:
            self.cancel()

        assert proc.stdout is not None
        for raw in proc.stdout:
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            self.lines.append(line)
            if self.on_line:
                self.on_line(line)
        proc.stdout.close()
        returncode = proc.wait()

        if self._cancelled:
            raise Cancelled("cancelled")
        return Completed(self.argv, returncode, "\n".join(self.lines))


def stream(
    argv: Sequence[str], on_line: Callable[[str], None] | None = None
) -> Completed:
    """Convenience wrapper for a stream that nobody needs to cancel."""
    return Stream(argv, on_line).run()
