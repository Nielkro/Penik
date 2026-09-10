#!/usr/bin/env python3
"""
Convenience entry point for running Penik E2E tests.
Automatically manages ephemeral test server lifecycle with PENIK_SQLITE_PATH=/tmp/penik_test.db.

Usage:
    python3 scripts/run_e2e.py                     # Auto-starts test server with /tmp/penik_test.db
    python3 scripts/run_e2e.py --url http://localhost:8143  # Tests an existing server
"""

import os
import sys
import time
import glob
import signal
import socket
import argparse
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import requests
from tests.e2e.test_runner import E2ETestSuite
import asyncio


def is_port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


def cleanup_test_db(db_path: str = "/tmp/penik_test.db"):
    for f in glob.glob(f"{db_path}*"):
        try:
            os.remove(f)
        except OSError:
            pass


def build_server_binary() -> str:
    binary_path = os.path.join(REPO_ROOT, "penik-server")
    print("\033[93m[build] Compiling ./penik-server binary...\033[0m")
    server_dir = os.path.join(REPO_ROOT, "server")
    res = subprocess.run(
        ["go", "build", "-o", binary_path, "cmd/server/main.go"],
        cwd=server_dir,
        capture_output=True,
        text=True,
    )
    if res.returncode != 0:
        print(f"\033[91m[build failed]\n{res.stderr}\033[0m")
        sys.exit(1)
    print("\033[92m[build] ./penik-server ready.\033[0m")
    return binary_path


def wait_for_server(url: str, timeout: float = 6.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = requests.get(f"{url}/api/v1/users/search", timeout=0.5)
            if r.status_code in (200, 401):
                return True
        except requests.RequestException:
            pass
        time.sleep(0.1)
    return False


def main():
    parser = argparse.ArgumentParser(description="Penik E2E Test Suite Runner")
    parser.add_argument("--url", default=None, help="Connect to existing server instead of starting a test instance")
    parser.add_argument("--ws-url", default=None, help="Custom WebSocket URL")
    parser.add_argument("--port", type=int, default=8145, help="Port for auto-started test server (default 8145)")
    parser.add_argument("--db-path", default="/tmp/penik_test.db", help="SQLite DB path for test server (default /tmp/penik_test.db)")
    parser.add_argument("--no-build", action="store_true", help="Skip compiling penik-server if it already exists")
    args = parser.parse_args()

    server_proc = None
    test_db_path = args.db_path

    if args.url:
        base_url = args.url.rstrip("/")
        ws_url = args.ws_url or f"{'wss' if base_url.startswith('https') else 'ws'}://{base_url.split('://', 1)[1]}/api/v1/ws"
    else:
        # Automatic ephemeral test server mode
        test_port = args.port
        if is_port_in_use(test_port):
            test_port = 8146

        binary = os.path.join(REPO_ROOT, "penik-server")
        if not os.path.exists(binary) or not args.no_build:
            binary = build_server_binary()

        cleanup_test_db(test_db_path)

        base_url = f"http://127.0.0.1:{test_port}"
        ws_url = f"ws://127.0.0.1:{test_port}/api/v1/ws"

        env = os.environ.copy()
        env["PENIK_SQLITE_PATH"] = test_db_path
        env["PORT"] = str(test_port)
        env["ENV"] = "development"
        env["ALLOWED_ORIGINS"] = f"http://localhost:{test_port},http://127.0.0.1:{test_port},https://web.penik.ru,https://penik.ru"

        print(f"\033[96m[server] Starting ephemeral test server on {base_url} (DB: {test_db_path})...\033[0m")
        server_proc = subprocess.Popen(
            [binary],
            cwd=REPO_ROOT,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        if not wait_for_server(base_url):
            print(f"\033[91m[error] Ephemeral test server failed to start on {base_url}\033[0m")
            if server_proc:
                server_proc.kill()
            cleanup_test_db(test_db_path)
            sys.exit(1)

        print(f"\033[92m[server] Test server is healthy and responding.\033[0m")

    try:
        suite = E2ETestSuite(base_url=base_url, ws_url=ws_url)
        exit_code = asyncio.run(suite.run_all())
    finally:
        if server_proc:
            print("\n\033[93m[server] Stopping ephemeral test server...\033[0m")
            server_proc.send_signal(signal.SIGTERM)
            try:
                server_proc.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                server_proc.kill()
            cleanup_test_db(test_db_path)
            print("\033[92m[server] Ephemeral test database cleaned up.\033[0m")

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
