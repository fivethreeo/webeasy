#!/usr/bin/env python3
"""Prepare and optionally publish Webeasy's APK and SideStore/AltStore source."""

import argparse
from datetime import datetime, timezone
import ftplib
import html
import ipaddress
import json
from pathlib import Path
import plistlib
import posixpath
import re
import shutil
import ssl
import sys
from urllib.parse import urlsplit
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){0,2}\Z")
BUNDLE_ID = "no.webeasy.app"


def read_ipa(file):
    try:
        with zipfile.ZipFile(file) as archive:
            paths = [name for name in archive.namelist()
                     if name.startswith("Payload/") and name.endswith(".app/Info.plist")]
            if len(paths) != 1:
                raise ValueError("IPA must contain exactly one app.")
            info = plistlib.loads(archive.read(paths[0]))
            prefix = paths[0].removesuffix("Info.plist")
            entitlements_path = prefix + "archived-expanded-entitlements.xcent"
            entitlements = {}
            if entitlements_path in archive.namelist():
                entitlements = plistlib.loads(archive.read(entitlements_path))
    except (OSError, zipfile.BadZipFile, plistlib.InvalidFileException) as error:
        raise ValueError("Cannot read the IPA.") from error
    if info.get("CFBundleIdentifier") != BUNDLE_ID:
        raise ValueError("IPA must be the Webeasy iOS app (no.webeasy.app).")
    for field in ("CFBundleShortVersionString", "CFBundleVersion"):
        if not isinstance(info.get(field), str) or not VERSION.fullmatch(info[field]):
            raise ValueError(f"IPA has an invalid {field}.")
    return info, {
        "entitlements": sorted(entitlements),
        "privacy": {key: value for key, value in info.items()
                    if key.endswith("UsageDescription") and isinstance(value, str)},
    }


def public_base(value, allow_http_development=False):
    parsed = urlsplit(value)
    local_http = False
    if allow_http_development and parsed.scheme == "http":
        try:
            local_http = parsed.hostname == "localhost" or ipaddress.ip_address(parsed.hostname).is_private
        except ValueError:
            pass
    if ((parsed.scheme != "https" and not local_http) or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or ".." in parsed.path.split("/")):
        raise ValueError("Public store URL must be an HTTPS directory URL without credentials.")
    return value.rstrip("/")


def prepare_store(apk, ipa, icon, styles, output, base_url, android_version, android_build,
                  allow_http_development=False):
    base_url = public_base(base_url, allow_http_development)
    if not VERSION.fullmatch(android_version) or android_build < 1:
        raise ValueError("Supply the APK's version name and positive version code.")
    try:
        with zipfile.ZipFile(apk) as archive:
            if not {"AndroidManifest.xml", "classes.dex"}.issubset(archive.namelist()):
                raise ValueError("APK must contain an Android manifest and application code.")
    except (OSError, zipfile.BadZipFile) as error:
        raise ValueError("Cannot read the APK.") from error
    info, permissions = read_ipa(ipa)
    if icon.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Supply a PNG app icon.")
    if not styles.is_file():
        raise ValueError("Website CSS is missing. Run npm run build first.")
    output.mkdir(parents=True, exist_ok=True)
    version = info["CFBundleShortVersionString"]
    build = info["CFBundleVersion"]
    ipa_name = f"Webeasy-ios-{version}-{build}.ipa"
    apk_name = f"Webeasy-android-{android_version}-{android_build}.apk"
    for source, name in [(apk, apk_name), (ipa, ipa_name), (icon, "icon.png"), (styles, "store.css")]:
        if source.resolve() != (output / name).resolve():
            shutil.copy2(source, output / name)
    source = {
        "name": "Webeasy Apps",
        "identifier": "no.webeasy.store",
        "subtitle": "Your pages, your hosting.",
        "description": "Install and update the Webeasy webpage editor.",
        "iconURL": f"{base_url}/icon.png",
        "sourceURL": f"{base_url}/source.json",
        "apps": [{
            "name": "Webeasy",
            "bundleIdentifier": BUNDLE_ID,
            "developerName": "Webeasy",
            "subtitle": "Edit and publish small websites.",
            "localizedDescription": "Generate HTML on your phone and publish it to your own hosting.",
            "iconURL": f"{base_url}/icon.png",
            "tintColor": "#193E37",
            "category": "productivity",
            "versions": [{
                "version": version,
                "buildVersion": build,
                "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "localizedDescription": f"Webeasy {version}, build {build}.",
                "downloadURL": f"{base_url}/{ipa_name}",
                "size": ipa.stat().st_size,
            }],
            "appPermissions": permissions,
        }],
        "news": [],
    }
    (output / "source.json").write_text(json.dumps(source, indent=2) + "\n", encoding="utf8")
    android = {
        "name": "Webeasy", "applicationId": BUNDLE_ID,
        "versionName": android_version, "versionCode": android_build,
        "downloadURL": f"{base_url}/{apk_name}", "size": apk.stat().st_size,
    }
    (output / "android.json").write_text(json.dumps(android, indent=2) + "\n", encoding="utf8")
    source_url = html.escape(f"{base_url}/source.json", quote=True)
    (output / "index.html").write_text(f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Webeasy Apps</title>
  <link rel="stylesheet" href="store.css">
</head>
<body>
  <main class="center stack site-shell">
    <header class="stack"><h1>Webeasy</h1><p>Your pages, your hosting.</p></header>
    <section class="box stack"><h2>Android</h2>
      <p>Version {html.escape(android_version)} (build {android_build}).</p>
      <a href="{apk_name}">Download Android APK</a>
      <p>Allow installation from this source when Android asks. Updates require the same signing key.</p>
    </section>
    <section class="box stack"><h2>iPhone and iPad</h2>
      <p>Version {html.escape(version)} (build {html.escape(build)}).</p>
      <p>Add this source URL in SideStore or AltStore:</p>
      <a href="source.json">{source_url}</a>
      <p>Your sideloading tool signs the app. A normal website cannot install the IPA directly.</p>
      <a href="{ipa_name}">Download iOS IPA</a>
    </section>
  </main>
</body>
</html>
""", encoding="utf8")
    # Publish the source last so it only advertises fully uploaded releases.
    return [output / name for name in
            [apk_name, ipa_name, "icon.png", "store.css", "android.json", "index.html", "source.json"]]


def load_connection(file):
    try:
        connection = json.loads(file.read_text(encoding="utf8"))
        protocol = connection["protocol"]
        host = connection["host"].strip()
        port = connection["port"]
        username = connection["username"].strip()
        password = connection["password"]
        directory = connection["directory"]
        if (protocol not in {"sftp", "ftps", "ftp"} or not host
                or re.search(r"[\s/\\?#@]", host)
                or isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535
                or not username or not isinstance(password, str) or not password
                or any(char in username + password for char in "\r\n\0")):
            raise ValueError
        remote_directory(directory)
    except (OSError, KeyError, TypeError, AttributeError, ValueError) as error:
        raise ValueError("Invalid hosting connection JSON. Check its connection fields.") from error
    return {**connection, "host": host, "username": username}


def remote_directory(value):
    if (not isinstance(value, str) or not value.startswith("/")
            or ".." in value.split("/") or any(char in value for char in "\r\n\0")):
        raise ValueError("Remote store directory must be an absolute path without traversal.")
    return posixpath.normpath(value)


class FTPPublisher:
    def __init__(self, connection):
        if connection["protocol"] == "ftps":
            self.client = ftplib.FTP_TLS(context=ssl.create_default_context(), timeout=30)
        else:
            self.client = ftplib.FTP(timeout=30)
        try:
            self.client.connect(connection["host"], connection["port"])
            self.client.login(connection["username"], connection["password"])
            if connection["protocol"] == "ftps":
                self.client.prot_p()
        except Exception:
            self.client.close()
            raise

    def ensure_directory(self, directory):
        self.client.cwd("/")
        for part in directory.split("/"):
            if not part:
                continue
            try:
                self.client.cwd(part)
            except ftplib.error_perm:
                self.client.mkd(part)
                self.client.cwd(part)

    def upload(self, file, directory):
        temporary = posixpath.join(directory, f".{file.name}.{uuid.uuid4().hex}.upload")
        try:
            with file.open("rb") as content:
                self.client.storbinary(f"STOR {temporary}", content)
            self.client.rename(temporary, posixpath.join(directory, file.name))
        except Exception:
            try:
                self.client.delete(temporary)
            except ftplib.all_errors:
                pass
            raise

    def close(self):
        self.client.close()


class SFTPPublisher:
    def __init__(self, connection, known_hosts=None):
        try:
            import paramiko
        except ImportError as error:
            raise ValueError("SFTP needs: pip install -r scripts/requirements-publish.txt") from error
        self.client = paramiko.SSHClient()
        self.client.load_system_host_keys()
        if known_hosts:
            self.client.load_host_keys(str(known_hosts))
        self.client.set_missing_host_key_policy(paramiko.RejectPolicy())
        try:
            self.client.connect(connection["host"], port=connection["port"],
                                username=connection["username"], password=connection["password"],
                                timeout=30, banner_timeout=30, auth_timeout=30,
                                look_for_keys=False, allow_agent=False)
            self.sftp = self.client.open_sftp()
        except Exception:
            self.client.close()
            raise

    def ensure_directory(self, directory):
        current = "/"
        for part in directory.split("/"):
            if not part:
                continue
            current = posixpath.join(current, part)
            try:
                self.sftp.stat(current)
            except FileNotFoundError:
                self.sftp.mkdir(current)

    def upload(self, file, directory):
        temporary = posixpath.join(directory, f".{file.name}.{uuid.uuid4().hex}.upload")
        destination = posixpath.join(directory, file.name)
        try:
            self.sftp.put(str(file), temporary)
            try:
                self.sftp.posix_rename(temporary, destination)
            except OSError:
                # Standard SFTP rename cannot overwrite: only use it for a new file.
                try:
                    self.sftp.stat(destination)
                except FileNotFoundError:
                    self.sftp.rename(temporary, destination)
                else:
                    raise ValueError("SFTP server must support atomic replacement for store updates.")
        except Exception:
            try:
                self.sftp.remove(temporary)
            except OSError:
                pass
            raise

    def close(self):
        self.sftp.close()
        self.client.close()


def publish(files, connection, directory, *, allow_insecure_ftp=False, known_hosts=None):
    if connection["protocol"] == "ftp" and not allow_insecure_ftp:
        raise ValueError("Plain FTP is unencrypted; use FTPS/SFTP or explicitly pass --allow-insecure-ftp.")
    publisher = (SFTPPublisher(connection, known_hosts) if connection["protocol"] == "sftp"
                 else FTPPublisher(connection))
    try:
        publisher.ensure_directory(directory)
        for file in files:
            publisher.upload(file, directory)
    finally:
        publisher.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--icon", type=Path, required=True, help="PNG app/store icon")
    parser.add_argument("--public-url", required=True, help="HTTPS URL of the webhost store directory")
    parser.add_argument("--android-version", required=True, help="Actual APK versionName")
    parser.add_argument("--android-build", type=int, required=True, help="Actual APK versionCode")
    parser.add_argument("--config", type=Path, default=ROOT / "website/hosting.local.json")
    parser.add_argument("--remote-directory", help="Defaults to the configured website directory + /store")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/store")
    parser.add_argument("--known-hosts", type=Path, help="Optional trusted SSH known_hosts file")
    parser.add_argument("--upload", action="store_true", help="Actually connect and upload; default only prepares files")
    parser.add_argument("--allow-insecure-ftp", action="store_true")
    parser.add_argument("--allow-http-development", action="store_true",
                        help="Allow HTTP store URLs only on localhost/private IPs for LAN testing")
    args = parser.parse_args()
    try:
        files = prepare_store(args.apk, args.ipa, args.icon, ROOT / "website/dist/assets/site.css",
                              args.output, args.public_url, args.android_version, args.android_build,
                              args.allow_http_development)
        print(f"Prepared {len(files)} store files in {args.output}.")
        if args.upload:
            connection = load_connection(args.config)
            directory = remote_directory(args.remote_directory or
                                         posixpath.join(connection["directory"], "store"))
            publish(files, connection, directory,
                    allow_insecure_ftp=args.allow_insecure_ftp, known_hosts=args.known_hosts)
            print("Uploaded store files. Verify public downloads before sharing the source.")
        else:
            print("No connection made. Pass --upload to publish.")
        print(f"SideStore/AltStore source: {public_base(args.public_url, args.allow_http_development)}/source.json")
    except (ValueError, OSError, ftplib.Error):
        # Authentication/library exceptions may contain sensitive connection details.
        print("Publishing failed. Check artifacts, arguments, trusted host keys, and hosting configuration.",
              file=sys.stderr)
        return 1
    except Exception:
        print("Publishing connection failed. No credentials were logged.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
