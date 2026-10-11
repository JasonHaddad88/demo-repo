"""Thin wrapper around subprocess so command execution can be faked in tests."""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from typing import Callable, Sequence


@dataclass(frozen=True)
class CommandResult:
    argv: tuple[str, ...]
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0

    @property
    def message(self) -> str:
        """Best available single-line explanation, for error reporting."""
        text = (self.stderr or self.stdout).strip()
        return text.splitlines()[0] if text else f"exit status {self.returncode}"


class Runner:
    """Runs external commands. Never uses a shell."""

    def __init__(self, timeout: float = 20.0) -> None:
        self.timeout = timeout

    def __call__(self, argv: Sequence[str], timeout: float | None = None) -> CommandResult:
        argv = [str(a) for a in argv]
        try:
            proc = subprocess.run(
                argv,
                capture_output=True,
                text=True,
                timeout=timeout or self.timeout,
                check=False,
            )
        except FileNotFoundError:
            return CommandResult(tuple(argv), 127, "", f"{argv[0]}: command not found")
        except PermissionError as exc:
            return CommandResult(tuple(argv), 126, "", str(exc))
        except subprocess.TimeoutExpired:
            return CommandResult(tuple(argv), 124, "", f"{argv[0]}: timed out")
        return CommandResult(tuple(argv), proc.returncode, proc.stdout, proc.stderr)

    @staticmethod
    def which(program: str) -> str | None:
        return shutil.which(program)


RunnerType = Callable[..., CommandResult]


class FakeRunner:
    """Test double: maps a command prefix to a canned result."""

    def __init__(self, responses: dict[tuple[str, ...], CommandResult] | None = None) -> None:
        self.responses = responses or {}
        self.calls: list[tuple[str, ...]] = []
        self.default = CommandResult((), 0, "", "")

    def add(self, argv: Sequence[str], stdout: str = "", returncode: int = 0, stderr: str = "") -> None:
        key = tuple(str(a) for a in argv)
        self.responses[key] = CommandResult(key, returncode, stdout, stderr)

    def __call__(self, argv, timeout=None) -> CommandResult:  # noqa: ANN001 - matches Runner
        key = tuple(str(a) for a in argv)
        self.calls.append(key)
        for length in range(len(key), 0, -1):
            hit = self.responses.get(key[:length])
            if hit is not None:
                return CommandResult(key, hit.returncode, hit.stdout, hit.stderr)
        return CommandResult(key, self.default.returncode, self.default.stdout, self.default.stderr)
