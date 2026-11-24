#!/usr/bin/env python3
"""Webeasy task runner: website, development hosting, native builds, and publishing."""

import argparse
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parent
VENV_PYTHON = ROOT / ".venv-dev-host" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def execute(command, env):
    return subprocess.run(command, cwd=ROOT, env=env, check=True).returncode


def supervise(commands, env):
    processes = []

    def interrupted(*_):
        raise KeyboardInterrupt

    previous_handler = signal.signal(signal.SIGTERM, interrupted)
    try:
        for command in commands:
            processes.append(subprocess.Popen(command, cwd=ROOT, env=env, start_new_session=os.name != "nt"))
        while True:
            for process in processes:
                status = process.poll()
                if status is not None:
                    return status or 1
            time.sleep(0.2)
    except KeyboardInterrupt:
        return 0
    finally:
        signal.signal(signal.SIGTERM, previous_handler)
        for process in processes:
            if process.poll() is None:
                if os.name == "nt":
                    process.terminate()
                else:
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
        for process in processes:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    process.kill()
                else:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.wait()


def ftp_command(env):
    if not VENV_PYTHON.is_file():
        raise ValueError("Development dependencies are missing. Run python3 run.py setup.")
    return [str(VENV_PYTHON), "scripts/dev_ftp.py", "--host",
            env.get("FTP_HOST", env.get("HOST", "192.168.1.2")),
            "--port", env.get("FTP_PORT", "2121")]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task", choices=["setup", "ts", "ftp", "server", "parallel",
                                       "build", "test", "android", "ios", "publish", "credentials", "npm"])
    parser.add_argument("arguments", nargs=argparse.REMAINDER, help="KEY=VALUE assignments and forwarded arguments")
    args = parser.parse_args(argv)
    env = os.environ.copy()
    forwarded = []
    for argument in args.arguments:
        if "=" in argument and not argument.startswith("-"):
            key, value = argument.split("=", 1)
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                parser.error("Invalid environment variable assignment.")
            env[key] = value
        else:
            forwarded.append(argument)
    env["WEBEASY_DEV_HOST"] = env.get("HOST", env.get("WEBEASY_DEV_HOST", "127.0.0.1"))
    npm_dev = ["npm", "run", "dev", "--workspace", "website"]
    try:
        if args.task == "setup":
            execute(["npm", "ci"], env)
            execute([sys.executable, "-m", "venv", str(ROOT / ".venv-dev-host")], env)
            return execute([str(VENV_PYTHON), "-m", "pip", "install",
                            "-r", "scripts/requirements-dev-host.txt",
                            "-r", "scripts/requirements-publish.txt"], env)
        if args.task in {"server", "parallel"}:
            if forwarded:
                parser.error("Use HOST=..., FTP_PORT=..., and RSBUILD_PORT=... for server settings.")
            env["HOST"] = env.get("HOST", "192.168.1.2")
            env["WEBEASY_DEV_HOST"] = env["HOST"]
            port = env.get("RSBUILD_PORT", "4173")
            print(f"Website uploads: http://{env['HOST']}:{port}/host/", flush=True)
            print(f"Connection QR: http://{env['HOST']}:{port}/provision.html (local machine only)", flush=True)
            print("Starting FTP and Rsbuild; Ctrl+C stops both.", flush=True)
            return supervise([ftp_command(env), npm_dev], env)
        if args.task == "ts":
            return execute([*npm_dev, *(["--", *forwarded] if forwarded else [])], env)
        if args.task == "ftp":
            return execute([*ftp_command(env), *forwarded], env)
        if args.task == "build":
            return execute(["npm", "run", "package:website", *forwarded], env)
        if args.task == "test":
            return execute(["npm", "test", *forwarded], env)
        if args.task == "npm":
            return execute(["npm", *forwarded], env)
        if args.task == "publish":
            python = str(VENV_PYTHON) if VENV_PYTHON.is_file() else sys.executable
            return execute([python, "scripts/publish_store.py", *forwarded], env)
        if args.task == "credentials":
            return execute([sys.executable, "scripts/credentials.py", *forwarded], env)
        if args.task == "android":
            wrapper = ROOT / "apps/android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
            tasks = forwarded if any(not item.startswith("-") for item in forwarded) else [
                ":app:assembleDebug", ":app:lintDebug", ":app:testDebugUnitTest", *forwarded,
            ]
            return execute([str(wrapper), "-p", "apps/android",
                            *tasks], env)
        if args.task == "ios":
            if sys.platform != "darwin":
                raise ValueError("iOS builds require macOS, Xcode, and XcodeGen.")
            execute(["npm", "run", "package:website"], env)
            execute(["xcodegen", "generate", "--spec", "apps/ios/project.yml"], env)
            return execute(["xcodebuild", "-project", "apps/ios/Webeasy.xcodeproj", "-scheme", "Webeasy",
                            *(forwarded or ["-sdk", "iphonesimulator", "CODE_SIGNING_ALLOWED=NO", "build"])], env)
    except subprocess.CalledProcessError as error:
        return error.returncode
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
