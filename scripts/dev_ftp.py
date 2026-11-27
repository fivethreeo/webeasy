#!/usr/bin/env python3
"""Run an isolated LAN-only development FTP host for Webeasy."""

import argparse
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile

from publish_store import load_connection


ROOT = Path(__file__).resolve().parents[1]


def configure(file, host, port):
    if not file.exists():
        connection = {
            "protocol": "ftp", "host": host, "port": port,
            "username": "webeasy", "password": secrets.token_urlsafe(24),
            "directory": "/public",
        }
        descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf8") as stream:
            json.dump(connection, stream, indent=2)
            stream.write("\n")
    connection = load_connection(file)
    if (connection["protocol"] != "ftp" or connection["host"] != host
            or connection["port"] != port or connection["directory"] not in {"/", "/public"}):
        raise ValueError("Existing hosting.local.json is not this development host; it was not overwritten.")
    if connection["directory"] == "/":
        connection["directory"] = "/public"
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf8", dir=file.parent,
                                         prefix=".hosting-local-", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(connection, stream, indent=2)
            stream.write("\n")
        try:
            os.replace(temporary, file)
        finally:
            temporary.unlink(missing_ok=True)
    return connection


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.1.2")
    parser.add_argument("--port", type=int, default=2121)
    args = parser.parse_args()
    try:
        from pyftpdlib.authorizers import DummyAuthorizer
        from pyftpdlib.handlers import FTPHandler
        from pyftpdlib.servers import FTPServer
    except ImportError:
        print("Install scripts/requirements-dev-host.txt in .venv-dev-host.", file=sys.stderr)
        return 1
    try:
        connection = configure(ROOT / "website/hosting.local.json", args.host, args.port)
        directory = ROOT / "dev-host"
        if directory.is_symlink():
            raise ValueError("The development host root must not be a symlink.")
        directory.mkdir(exist_ok=True)
        public = directory / "public"
        backups = directory / "backups"
        if public.is_symlink() or backups.is_symlink():
            raise ValueError("Development public and backup directories must not be symlinks.")
        public.mkdir(exist_ok=True)
        backups.mkdir(exist_ok=True)
        landing = public / "index.html"
        if not landing.exists():
            with landing.open("x", encoding="utf8") as stream:
                stream.write("""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Webeasy development host</title><link rel="stylesheet" href="/assets/site.css"></head>
<body><main class="center stack site-shell"><h1>Webeasy development host</h1>
<p>Upload website files using the FTP connection QR. They are served here immediately.</p>
<a href="store/">App store downloads</a></main></body></html>
""")
        authorizer = DummyAuthorizer()
        authorizer.add_user(connection["username"], connection["password"], str(directory),
                            perm="elradfmwMT")
        handler = type("DevelopmentFTPHandler", (FTPHandler,), {})
        handler.authorizer = authorizer
        handler.passive_ports = range(30000, 30010)
        handler.masquerade_address = args.host
        handler.permit_foreign_addresses = False
        handler.permit_privileged_ports = False
        # iOS FilesProvider pools several control connections while publishing.
        # Leave room for that pool and expire abandoned idle sessions promptly.
        handler.timeout = 60
        server = FTPServer((args.host, args.port), handler)
        server.max_cons = 64
        server.max_cons_per_ip = 16
        print(f"Development FTP: {args.host}:{args.port}; root: {directory}", flush=True)
        print("Website destination: /public; private backup directory: /backups.", flush=True)
        print("Credentials are in the gitignored hosting.local.json; plain FTP is for trusted LAN testing only.",
              flush=True)
        server.serve_forever()
    except (ValueError, OSError):
        print("Cannot start development FTP. Check address, port, and local config; no existing config was overwritten.",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
