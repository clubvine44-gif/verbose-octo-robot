"""TESSA Windows <-> Android transport smoke test.

Runs on the Windows PC with its local installation. Never logs or prints the
authentication token. Performs no keyboard/mouse input.
"""
from __future__ import annotations

import argparse
import asyncio
import hmac
import hashlib
import json
import os
import pathlib
import secrets
import shutil
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

from aiohttp import ClientSession, ClientTimeout, WSMsgType

PORT = 18766
BASE = pathlib.Path(os.environ.get("LOCALAPPDATA") or pathlib.Path.home() / ".config") / "TESSA"
TOKEN_FILE = BASE / "mobile-bridge.token"


def adb_path() -> str | None:
    return shutil.which("adb") or (
        str(pathlib.Path("G:/AndroidDev/Sdk/platform-tools/adb.exe"))
        if pathlib.Path("G:/AndroidDev/Sdk/platform-tools/adb.exe").exists() else None
    )


def adb_device_report() -> dict:
    adb = adb_path()
    if not adb:
        return {"ok": False, "reason": "adb.exe not found"}
    try:
        result = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=8)
        detected = [
            row.split()[0] for row in result.stdout.splitlines()
            if len(row.split()) > 1 and row.split()[1] == "device"
        ]
        if result.returncode != 0:
            return {"ok": False, "reason": "adb devices exited nonzero"}
        reverse = subprocess.run([adb, "reverse", "--list"], capture_output=True, text=True, timeout=8)
        return {
            "ok": len(detected) == 1,
            "devices": len(detected),
            "serial": detected[0] if len(detected) == 1 else None,
            "reverse_18766": any("tcp:18766" in line for line in reverse.stdout.splitlines()),
        }
    except (OSError, subprocess.SubprocessError) as exc:
        return {"ok": False, "reason": type(exc).__name__}


async def check_server(port: int) -> dict:
    if not TOKEN_FILE.exists():
        return {"ok": False, "stage": "token_missing"}
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if len(token) < 32:
        return {"ok": False, "stage": "token_invalid"}
    url = f"http://127.0.0.1:{port}"
    nonce = secrets.token_urlsafe(18)
    report = {"ok": False, "stage": "initial"}
    try:
        async with ClientSession(timeout=ClientTimeout(total=9)) as session:
            async with session.get(url + "/probe", params={"nonce": nonce}) as resp:
                payload = await resp.json()
                expected = hmac.new(token.encode(), nonce.encode(), hashlib.sha256).hexdigest()
                if resp.status != 200 or payload.get("bridge") != "TESSA_BRIDGE_1":
                    return {"ok": False, "stage": "probe_wrong_service", "status": resp.status}
                if not hmac.compare_digest(payload.get("proof", ""), expected):
                    return {"ok": False, "stage": "probe_auth_mismatch"}
                report["probe"] = True
            async with session.ws_connect(url + "/ws", params={"token": token}, heartbeat=4) as ws:
                hello = await ws.receive_json(timeout=4)
                if hello.get("type") != "hello":
                    return {"ok": False, "stage": "ws_handshake"}
                await ws.send_json({"type": "ping"})
                pong = await ws.receive_json(timeout=4)
                if pong.get("type") != "pong":
                    return {"ok": False, "stage": "ws_ping"}
                report["websocket"] = True
            async with session.get(url + "/health", params={"token": token}) as resp:
                health = await resp.json()
                report["viewers"] = health.get("viewers")
                report["monitor_seen"] = bool(health.get("monitor"))
                report["fps_target"] = health.get("fps_target")
            async with session.get(url + "/stream", params={"token": token}) as resp:
                if resp.status != 200:
                    return {"ok": False, "stage": "video_http", "status": resp.status}
                chunk = await resp.content.read(8192)
                if b"--frame" not in chunk or b"image/jpeg" not in chunk:
                    return {"ok": False, "stage": "video_frames"}
                report["mjpeg"] = True
    except (OSError, ValueError, asyncio.TimeoutError) as exc:
        return {"ok": False, "stage": "transport_failure", "detail": type(exc).__name__}
    report.update(ok=True, stage="ready")
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = {"android_usb": adb_device_report(), "bridge": asyncio.run(check_server(args.port))}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["bridge"]["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
