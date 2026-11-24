#!/usr/bin/env python3
"""Configure hosting.local.json locally without exposing a password editor on the web."""

import argparse
import getpass
import json
import os
from pathlib import Path
import sys
import tempfile
import warnings

from publish_store import load_connection


ROOT = Path(__file__).resolve().parents[1]


def prompt_connection(current):
    def field(label, default=""):
        value = input(f"{label}" + (f" [{default}]" if default else "") + ": ").strip()
        return value or default

    def secret(label):
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            try:
                return getpass.getpass(label)
            except getpass.GetPassWarning as error:
                raise ValueError("Password entry requires a local terminal with echo disabled.") from error

    protocol = field("Protocol (sftp, ftps, ftp)", current.get("protocol", "sftp"))
    default_port = current.get("port") if protocol == current.get("protocol") else (22 if protocol == "sftp" else 21)
    host = field("Server hostname", current.get("host", ""))
    port = int(field("Port", str(default_port)))
    username = field("Username", current.get("username", ""))
    directory = field("Remote folder", current.get("directory", "/public_html"))
    password = secret("Password (blank keeps the current password): " if current else "Password: ")
    if not password and current:
        password = current["password"]
    elif password != secret("Confirm password: "):
        raise ValueError("Passwords did not match; nothing was saved.")
    return {**current, "protocol": protocol, "host": host, "port": port,
            "username": username, "password": password, "directory": directory}


def save_connection(file, connection):
    if file.is_symlink():
        raise ValueError("Refusing to replace a symlink credential file.")
    descriptor, temporary = tempfile.mkstemp(prefix=".hosting-", suffix=".json", dir=file.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "w", encoding="utf8") as stream:
            json.dump(connection, stream, indent=2)
            stream.write("\n")
        load_connection(temporary)
        os.replace(temporary, file)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "website/hosting.local.json")
    args = parser.parse_args()
    try:
        current = load_connection(args.config) if args.config.exists() else {}
        connection = prompt_connection(current)
        save_connection(args.config, connection)
    except (OSError, ValueError, EOFError):
        print("Credentials were not saved. Check the input and file location.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled; nothing was saved.", file=sys.stderr)
        return 130
    print("Saved private hosting JSON. Reload the QR page; restart development FTP if its login changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
