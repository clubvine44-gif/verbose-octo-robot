"""Fast isolated tests for the mobile bridge's public wire contract."""
from __future__ import annotations
import struct
import sys
import types
from unittest.mock import patch
import unittest
from aiohttp.test_utils import TestClient, TestServer
from server import create_app, pcm_rms

TOKEN = "TEST_" + "x" * 36


class AudioMathTests(unittest.TestCase):
    def test_rms_zero(self):
        self.assertEqual(pcm_rms(bytes(2048)), 0)

    def test_rms_full_scale(self):
        self.assertEqual(pcm_rms(struct.pack("<hhhh", 1000, -1000, 1000, -1000)), 1000)

    def test_empty_and_odd_bytes(self):
        self.assertEqual(pcm_rms(b""), 0)
        self.assertEqual(pcm_rms(bytes([127])), 0)


class WebsocketContractTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.client = TestClient(TestServer(create_app(TOKEN)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    async def test_health_requires_key(self):
        for suffix in ("", "?token=wrong"):
            resp = await self.client.get("/health" + suffix)
            self.assertEqual(resp.status, 401)
            await resp.release()
        resp = await self.client.get("/health?token=" + TOKEN)
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["connected"])
        self.assertEqual(body["viewers"], 0)

    async def test_control_channel_handshake_ping_and_validation(self):
        async with self.client.ws_connect("/ws?token=" + TOKEN) as ws:
            first = await ws.receive_json(timeout=3)
            self.assertEqual(first["type"], "hello")
            await ws.send_json({"type": "ping"})
            self.assertEqual((await ws.receive_json(timeout=3))["type"], "pong")
            await ws.send_json({"type": "unrecognized"})
            msg = await ws.receive_json(timeout=3)
            self.assertEqual(msg["type"], "status")
            self.assertFalse(msg["ok"])

    async def test_video_capture_does_not_block_control_ping(self):
        class FakeRaw:
            size = (64, 36)
            rgb = bytes([20, 35, 58]) * (64 * 36)

        class FakeMSS:
            def __init__(self):
                self.monitors = [None, {"left": 0, "top": 0, "width": 64, "height": 36}]
            def __enter__(self):
                return self
            def __exit__(self, exc_type, exc, tb):
                return False
            def grab(self, monitor):
                return FakeRaw()

        module = types.ModuleType("mss")
        module.mss = FakeMSS
        with patch.dict(sys.modules, {"mss": module}):
            stream = await self.client.get("/stream?token=" + TOKEN)
            self.assertEqual(stream.status, 200)
            self.assertIn("multipart/x-mixed-replace", stream.headers.get("Content-Type", ""))
            sample = await stream.content.read(400)
            self.assertIn(b"--frame", sample)
            self.assertIn(b"image/jpeg", sample)
            async with self.client.ws_connect("/ws?token=" + TOKEN) as ws:
                self.assertEqual((await ws.receive_json(timeout=2))["type"], "hello")
                await ws.send_json({"type": "ping"})
                self.assertEqual((await ws.receive_json(timeout=2))["type"], "pong")
            stream.close()

    async def test_unauthorized_websocket(self):
        resp = await self.client.get("/ws?token=bad")
        self.assertEqual(resp.status, 401)
        await resp.release()


if __name__ == "__main__":
    unittest.main()
