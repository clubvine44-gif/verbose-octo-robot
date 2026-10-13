"""TESSA Android bridge. Experimental; use only over trusted encrypted overlay/LAN."""
from __future__ import annotations

import argparse
import asyncio
import math
from array import array
import hmac
import io
import json
import os
import secrets
import sys
import time
import threading
import wave
from pathlib import Path
from typing import Any

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web
from protocol import key_sequence, normalized_point

BASE = Path(os.environ.get("LOCALAPPDATA") or (Path.home() / ".config")) / "TESSA"
TOKEN_PATH = BASE / "mobile-bridge.token"
PORT = 8766


def pcm_rms(data: bytes) -> int:
    """RMS of signed little-endian 16-bit PCM; compatible with Python 3.13+."""
    if not data or len(data) % 2:
        return 0
    values = array("h")
    values.frombytes(data)
    if sys.byteorder != "little":
        values.byteswap()
    return math.isqrt(sum(int(v) * int(v) for v in values) // len(values))


def load_token() -> str:
    BASE.mkdir(parents=True, exist_ok=True)
    if not TOKEN_PATH.exists():
        token = secrets.token_urlsafe(32)
        fd = os.open(str(TOKEN_PATH), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(token)
    token = TOKEN_PATH.read_text(encoding="utf-8").strip()
    if len(token) < 32:
        raise RuntimeError("Bridge authentication token is too short")
    return token


def verify(request: web.Request) -> bool:
    given = request.query.get("token", "")
    actual = request.app["token"]
    return bool(given) and hmac.compare_digest(actual, given)


def denied() -> web.Response:
    return web.Response(status=401, text="Unauthorized")


def tessa_pipe(payload: dict[str, Any], timeout: float = 1.5) -> dict[str, Any]:
    source = Path.home() / "Documents" / "TESSA" / "src"
    if not source.is_dir():
        raise RuntimeError("Installed TESSA project not found")
    if str(source) not in sys.path:
        sys.path.insert(0, str(source))
    from tessa.ipc import request as ipc_request
    return ipc_request(payload, timeout=timeout)


class Bridge:
    def __init__(self, token: str, fps: int = 15) -> None:
        self.token = token
        self.fps = max(3, min(25, fps))
        self.clients: set[web.WebSocketResponse] = set()
        self.inputs = asyncio.Lock()
        self.recognition = asyncio.Lock()
        self.monitor: dict[str, int] | None = None
        self.max_width = 1280

    async def notify(self, payload: dict[str, Any]) -> None:
        dead = []
        for ws in tuple(self.clients):
            try:
                await ws.send_json(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.clients.discard(ws)

    async def stream(self, request: web.Request) -> web.StreamResponse:
        """Frame producer is isolated from the asyncio control/audio loop.

        Only the freshest complete JPEG is sent; slow phones never accumulate a
        delayed backlog. MSS creates and uses its Windows capture context in
        the same OS thread to avoid cross-thread GDI failures.
        """
        if not verify(request):
            return denied()
        try:
            import mss
            from PIL import Image
        except ImportError:
            return web.Response(status=503, text="Missing mss/Pillow dependencies")
        response = web.StreamResponse(
            status=200,
            headers={
                "Content-Type": "multipart/x-mixed-replace; boundary=frame",
                "Cache-Control": "no-store, max-age=0",
                "Pragma": "no-cache",
                "X-Content-Type-Options": "nosniff",
                "Connection": "keep-alive",
            },
        )
        await response.prepare(request)
        frames: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=1)
        stop = threading.Event()
        loop = asyncio.get_running_loop()

        def newest(blob: bytes | None) -> None:
            if stop.is_set():
                return
            try:
                if frames.full():
                    frames.get_nowait()
                frames.put_nowait(blob)
            except (asyncio.QueueEmpty, asyncio.QueueFull):
                pass

        def producer() -> None:
            try:
                with mss.mss() as grabber:
                    monitor = grabber.monitors[1]
                    self.monitor = {k: int(monitor[k]) for k in ("top", "left", "width", "height")}
                    while not stop.is_set():
                        started = time.monotonic()
                        raw = grabber.grab(monitor)
                        frame = Image.frombytes("RGB", raw.size, raw.rgb)
                        if frame.width > self.max_width:
                            width = self.max_width
                            height = max(1, round(frame.height * width / frame.width))
                            frame = frame.resize((width, height), Image.Resampling.BILINEAR)
                        output = io.BytesIO()
                        frame.save(output, "JPEG", quality=72, optimize=False)
                        loop.call_soon_threadsafe(newest, output.getvalue())
                        stop.wait(max(0.0, 1.0 / self.fps - (time.monotonic() - started)))
            except Exception as exc:
                print("capture_failure:", type(exc).__name__, str(exc), file=sys.stderr)
            finally:
                try:
                    loop.call_soon_threadsafe(newest, None)
                except RuntimeError:
                    pass

        thread = threading.Thread(target=producer, name="TessaDesktopCapture", daemon=True)
        thread.start()
        try:
            while request.transport is not None and not request.transport.is_closing():
                try:
                    blob = await asyncio.wait_for(frames.get(), timeout=8)
                except asyncio.TimeoutError:
                    break
                if blob is None:
                    break
                await response.write(
                    b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                    + str(len(blob)).encode("ascii")
                    + b"\r\n\r\n" + blob + b"\r\n"
                )
        except (ConnectionError, ConnectionResetError, asyncio.CancelledError, BrokenPipeError):
            pass
        finally:
            stop.set()
            await asyncio.to_thread(thread.join, 1.5)
        return response

    @staticmethod
    def inject_unicode(text: str) -> None:
        """Inject plain Unicode text to focused Windows control (no clipboard exposure)."""
        import ctypes
        from ctypes import wintypes
        if os.name != "nt":
            raise RuntimeError("Windows input required")

        class KBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                        ("wParamH", wintypes.WORD)]

        class INPUTUNION(ctypes.Union):
            _fields_ = [("ki", KBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("data",)
            _fields_ = [("type", wintypes.DWORD), ("data", INPUTUNION)]

        KEYEVENTF_UNICODE = 0x0004
        KEYEVENTF_KEYUP = 0x0002
        # UTF-16 code units support Cyrillic and surrogate-pair characters.
        encoded = text.encode("utf-16-le")
        for offset in range(0, len(encoded), 2):
            unit = int.from_bytes(encoded[offset:offset + 2], "little")
            downs = INPUT(type=1, data=INPUTUNION(ki=KBDINPUT(0, unit, KEYEVENTF_UNICODE, 0, 0)))
            ups = INPUT(type=1, data=INPUTUNION(ki=KBDINPUT(0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0, 0)))
            events = (INPUT * 2)(downs, ups)
            if ctypes.windll.user32.SendInput(2, events, ctypes.sizeof(INPUT)) != 2:
                raise RuntimeError("Windows SendInput failed")

    def interact(self, payload: dict[str, Any]) -> str:
        import pyautogui
        pyautogui.FAILSAFE = True
        kind = payload.get("type")
        if kind == "click":
            m = self.monitor
            if not m:
                raise RuntimeError("No desktop stream yet")
            button = payload.get("button", "left")
            if button not in ("left", "right"):
                raise ValueError("unsupported_mouse_button")
            x, y = normalized_point(payload.get("x"), payload.get("y"),
                                    m["left"], m["top"], m["width"], m["height"])
            pyautogui.click(x=x, y=y, button=button)
            return "Нажатие отправлено"
        if kind == "key":
            keys = key_sequence(payload.get("key"))
            if len(keys) == 1:
                pyautogui.press(keys[0])
            else:
                pyautogui.hotkey(*keys)
            return "Клавиши отправлены"
        if kind == "text":
            text = payload.get("text")
            if not isinstance(text, str) or len(text) > 4000 or not text:
                raise ValueError("invalid_text")
            self.inject_unicode(text)
            return "Текст отправлен"
        raise ValueError("unknown_input")

    async def tessa_command(self, command: str, client: web.WebSocketResponse | None = None) -> None:
        async def send(obj: dict[str, Any]) -> None:
            if client is None:
                await self.notify(obj)
            elif not client.closed:
                await client.send_json(obj)
        if not command.strip() or len(command) > 4000:
            await send({"type": "status", "ok": False, "message": "Не выполнено: неверная команда"})
            return
        try:
            initial = await asyncio.to_thread(tessa_pipe, {"op": "submit", "command": command}, 2.0)
            if not initial.get("ok"):
                raise RuntimeError(initial.get("message") or initial.get("error") or "TESSA rejected command")
            if initial.get("needs_clarification"):
                await send({"type": "status", "ok": False, "message": initial.get("clarification") or "Команда неоднозначна"})
                return
            task_id = initial.get("task_id")
            if not task_id:
                raise RuntimeError("No task ID returned")
            started = time.monotonic()
            while time.monotonic() - started < 35:
                await asyncio.sleep(.24)
                current = await asyncio.to_thread(tessa_pipe, {"op": "task", "task_id": task_id}, 1.0)
                if not current.get("ok"):
                    raise RuntimeError(current.get("error", "Task query failed"))
                task = current.get("task") or {}
                state = str(task.get("state", "")).lower()
                if state in ("succeeded", "success", "completed", "done"):
                    await send({"type": "status", "ok": True, "message": "Выполнено"})
                    return
                if state in ("failed", "rejected", "cancelled", "canceled", "interrupted"):
                    await send({"type": "status", "ok": False,
                                "message": "Не выполнено: " + str(task.get("message") or state)[:200]})
                    return
                if state in ("awaiting_confirmation", "waiting_confirmation", "pending_confirmation", "confirmation_required"):
                    await send({"type": "status", "ok": False,
                                "message": "Требуется подтверждение в окне TESSA на ПК"})
                    return
            await send({"type": "status", "ok": False, "message": "Задача ещё выполняется; результат не подтверждён"})
        except Exception as exc:
            await send({"type": "status", "ok": False,
                        "message": "Не выполнено: " + str(exc)[:180]})

    async def controls(self, request: web.Request) -> web.StreamResponse:
        if not verify(request):
            return denied()
        ws = web.WebSocketResponse(heartbeat=20, receive_timeout=90, max_msg_size=8000)
        await ws.prepare(request)
        self.clients.add(ws)
        await ws.send_json({"type": "hello", "screen": self.monitor or {}})
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    payload = json.loads(msg.data)
                    if not isinstance(payload, dict):
                        raise ValueError("not_an_object")
                    kind = payload.get("type")
                    if kind == "ping":
                        await ws.send_json({"type": "pong"})
                    elif kind == "command":
                        asyncio.create_task(self.tessa_command(str(payload.get("text", "")), ws))
                    elif kind in ("click", "key", "text"):
                        async with self.inputs:
                            note = await asyncio.to_thread(self.interact, payload)
                        await ws.send_json({"type": "status", "ok": True, "message": note})
                    else:
                        raise ValueError("unsupported_action")
                except Exception as exc:
                    await ws.send_json({"type": "status", "ok": False,
                                        "message": "Не выполнено: " + str(exc)[:160]})
        finally:
            self.clients.discard(ws)
        return ws

    @staticmethod
    def wav_data(pcm: bytes) -> bytes:
        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(16000)
            output.writeframes(pcm)
        return buffer.getvalue()

    @staticmethod
    def groq_key() -> str | None:
        try:
            source = Path.home() / "Documents" / "TESSA" / "src"
            if str(source) not in sys.path:
                sys.path.insert(0, str(source))
            from tessa.ai_provider import load_key
            return load_key()
        except Exception:
            return None

    async def recognize(self, pcm: bytes) -> None:
        async with self.recognition:
            key = await asyncio.to_thread(self.groq_key)
            if not key:
                await self.notify({"type": "status", "ok": False,
                                   "message": "Нет ключа распознавания речи Groq в TESSA"})
                return
            try:
                form = __import__("aiohttp").FormData()
                form.add_field("file", self.wav_data(pcm), filename="speech.wav", content_type="audio/wav")
                form.add_field("model", "whisper-large-v3-turbo")
                form.add_field("language", "ru")
                form.add_field("response_format", "json")
                async with ClientSession(timeout=ClientTimeout(total=25)) as session:
                    async with session.post(
                        "https://api.groq.com/openai/v1/audio/transcriptions",
                        data=form, headers={"Authorization": f"Bearer {key}"}
                    ) as result:
                        if result.status != 200:
                            raise RuntimeError("Ошибка STT: HTTP " + str(result.status))
                        response = await result.json()
                text = str(response.get("text", "")).strip()
                if len(text) < 2:
                    return
                await self.notify({"type": "transcript", "text": text})
                await self.tessa_command(text)
            except Exception as exc:
                await self.notify({"type": "status", "ok": False, "message": "Речь: " + str(exc)[:150]})

    async def audio(self, request: web.Request) -> web.StreamResponse:
        if not verify(request):
            return denied()
        ws = web.WebSocketResponse(heartbeat=20, receive_timeout=70, max_msg_size=16384)
        await ws.prepare(request)
        background = 170.0
        recording = bytearray()
        leading = bytearray()
        active_ms = 0
        silent_ms = 0
        try:
            async for msg in ws:
                if msg.type != WSMsgType.BINARY:
                    continue
                pcm = bytes(msg.data)
                if len(pcm) % 2 or not pcm or len(pcm) > 8192:
                    continue
                duration_ms = round(len(pcm) / (16000 * 2) * 1000)
                rms = pcm_rms(pcm)
                talking = rms > max(400, background * 2.8)
                if not talking:
                    background = .99 * background + .01 * rms
                if talking:
                    if not recording:
                        recording.extend(leading)
                    recording.extend(pcm)
                    active_ms += duration_ms
                    silent_ms = 0
                elif recording:
                    recording.extend(pcm)
                    silent_ms += duration_ms
                else:
                    leading.extend(pcm)
                    if len(leading) > 12000:
                        del leading[:len(leading) - 12000]
                if recording and (silent_ms >= 650 or len(recording) >= 16000 * 2 * 13):
                    if active_ms >= 350:
                        asyncio.create_task(self.recognize(bytes(recording)))
                    recording = bytearray()
                    active_ms = silent_ms = 0
                    leading.clear()
        except (ConnectionError, asyncio.CancelledError):
            pass
        return ws

    async def health(self, request: web.Request) -> web.Response:
        if not verify(request):
            return denied()
        return web.json_response({"connected": True, "monitor": self.monitor,
                                  "viewers": len(self.clients), "fps_target": self.fps})


def create_app(token: str, fps: int = 15) -> web.Application:
    engine = Bridge(token, fps)
    app = web.Application(client_max_size=65536)
    app["token"] = token
    app["bridge"] = engine
    app.router.add_get("/ws", engine.controls)
    app.router.add_get("/audio", engine.audio)
    app.router.add_get("/stream", engine.stream)
    app.router.add_get("/health", engine.health)
    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="TESSA Android private Windows bridge")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--fps", type=int, default=15)
    options = parser.parse_args()
    token = load_token()
    print("TESSA mobile bridge starting. Token path:", TOKEN_PATH)
    print("Bind address:", options.bind, "Port:", options.port)
    print("Use LAN/Tailscale ONLY, never forward this port publicly.")
    web.run_app(create_app(token, options.fps), host=options.bind, port=options.port, access_log=None)


if __name__ == "__main__":
    main()
