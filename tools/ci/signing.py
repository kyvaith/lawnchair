#!/usr/bin/env python3
"""Load CI signing material from step-scoped environment variables, never shell text."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import stat
import sys
import tempfile


PREFIX = "lawnchair-signing-"
STATE_NAME = "state.json"
KEY_NAME = "key.jks"
PROPERTIES_NAME = "keystore.properties"
REQUIRED = ("KEYSTORE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")


class SigningError(Exception):
    """An actionable error that contains no supplied signing values."""


def escape_property(value: str) -> str:
    """Serialize an exact value for java.util.Properties.load(InputStream)."""
    escapes = {"\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t", "\f": "\\f"}
    result = []
    for character in value:
        if character in escapes:
            result.append(escapes[character])
        elif character in " :=#!":
            result.append("\\" + character)
        elif not 0x20 <= ord(character) <= 0x7E:
            encoded = character.encode("utf-16-be")
            result.extend("\\u%04X" % int.from_bytes(encoded[i:i + 2], "big") for i in range(0, len(encoded), 2))
        else:
            result.append(character)
    return "".join(result)


def write_private(path: Path, contents: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(contents)
    path.chmod(0o600)


def append_actions_file(name: str, entry: str, value: str) -> None:
    destination = os.environ.get(name)
    if not destination:
        raise SigningError("GitHub Actions environment/output files are not configured.")
    if "\n" in value or "\r" in value:
        raise SigningError("The generated signing path cannot contain line breaks.")
    with open(destination, "a", encoding="utf-8") as output:
        output.write(f"{entry}={value}\n")


def runner_temp() -> Path:
    value = os.environ.get("RUNNER_TEMP")
    if not value or not Path(value).is_absolute() or not Path(value).is_dir():
        raise SigningError("RUNNER_TEMP must name an existing absolute temporary directory.")
    return Path(value).resolve()


def validated_state(value: str) -> Path:
    candidate = Path(value)
    temp = runner_temp()
    if not candidate.is_absolute() or candidate.name != STATE_NAME:
        raise SigningError("Refusing an unrecognized signing cleanup path.")
    directory = candidate.parent
    if directory.is_symlink() or candidate.is_symlink():
        raise SigningError("Refusing a symbolic-link signing cleanup path.")
    if directory.resolve().parent != temp or not directory.name.startswith(PREFIX):
        raise SigningError("Signing cleanup must stay inside its generated RUNNER_TEMP directory.")
    return candidate


def cleanup_state(value: str) -> None:
    state_file = validated_state(value)
    if not state_file.exists():
        return
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise SigningError("Refusing invalid signing cleanup metadata.") from None
    if state != {"schema": 1, "created_by": "lawnchair-ci-signing"}:
        raise SigningError("Refusing unrecognized signing cleanup metadata.")
    # Delete only the three files created here. Never recursively delete a supplied path.
    for name in (KEY_NAME, PROPERTIES_NAME):
        path = state_file.parent / name
        if path.is_symlink():
            path.unlink()
        elif path.exists():
            if not stat.S_ISREG(path.stat().st_mode):
                raise SigningError("Unexpected object in the signing temporary directory.")
            path.unlink()
    state_file.unlink()
    try:
        state_file.parent.rmdir()
    except OSError:
        raise SigningError("Signing files were removed, but unexpected temporary files remain.") from None


def prepare() -> None:
    missing = [name for name in REQUIRED if not os.environ.get(name)]
    if missing:
        raise SigningError("Required signing secrets are missing: " + ", ".join(missing))
    if not os.environ.get("GITHUB_ENV") or not os.environ.get("GITHUB_OUTPUT"):
        raise SigningError("This signing helper requires GitHub Actions environment/output files.")
    try:
        key = base64.b64decode("".join(os.environ["KEYSTORE"].split()), validate=True)
    except (binascii.Error, ValueError):
        raise SigningError("KEYSTORE must contain a valid base64-encoded signing keystore.") from None
    if not key:
        raise SigningError("The decoded signing keystore is empty.")

    temp = runner_temp()
    previous_umask = os.umask(0o077)
    state_file = None
    try:
        directory = Path(tempfile.mkdtemp(prefix=PREFIX, dir=temp))
        directory.chmod(0o700)
        state_file = directory / STATE_NAME
        write_private(state_file, json.dumps({"schema": 1, "created_by": "lawnchair-ci-signing"}).encode("utf-8"))
        # Publish cleanup ownership before any key bytes are written, including interrupted setup.
        append_actions_file("GITHUB_OUTPUT", "cleanup_state", str(state_file))
        write_private(directory / KEY_NAME, key)
        properties = {
            "storeFile": (directory / KEY_NAME).as_posix(),
            "storePassword": os.environ["KEYSTORE_PASSWORD"],
            "keyAlias": os.environ["KEY_ALIAS"],
            "keyPassword": os.environ["KEY_PASSWORD"],
        }
        contents = "".join(f"{name}={escape_property(value)}\n" for name, value in properties.items())
        write_private(directory / PROPERTIES_NAME, contents.encode("ascii"))
        # Only paths cross step boundaries; passwords and key bytes stay in private files.
        append_actions_file("GITHUB_ENV", "LAWNCHAIR_KEYSTORE_PROPERTIES", str(directory / PROPERTIES_NAME))
    except Exception:
        if state_file is not None and state_file.exists():
            cleanup_state(str(state_file))
        raise
    finally:
        os.umask(previous_umask)
    print("Release signing material prepared in the runner temporary directory.")


def cleanup() -> None:
    value = os.environ.get("SIGNING_STATE", "")
    if value:
        cleanup_state(value)
    if os.environ.get("GITHUB_ENV"):
        append_actions_file("GITHUB_ENV", "LAWNCHAIR_KEYSTORE_PROPERTIES", "")
    print("Temporary release signing material cleaned up.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "cleanup"))
    action = parser.parse_args().action
    try:
        {"prepare": prepare, "cleanup": cleanup}[action]()
    except SigningError as error:
        print(f"Signing setup failed: {error}", file=sys.stderr)
        return 1
    except OSError:
        print("Signing setup failed: temporary signing files could not be accessed.", file=sys.stderr)
        return 1
    except Exception:
        print("Signing setup failed: signing material could not be prepared safely.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
