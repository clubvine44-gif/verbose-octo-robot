"""Fast isolated tests for the mobile bridge's public wire contract."""
from __future__ import annotations
import struct
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

    async def test_unauthorized_websocket(self):
        resp = await self.client.get("/ws?token=bad")
        self.assertEqual(resp.status, 401)
        await resp.release()


if __name__ == "__main__":
    unittest.main()
