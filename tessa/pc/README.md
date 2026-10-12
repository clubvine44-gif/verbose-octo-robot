# TESSA Android ↔ Windows bridge (experimental)

This repository's `tessa/android/` is the Android UI/client. The Python server in this directory provides authenticated MJPEG desktop preview, mouse/keyboard input and forwarding commands to the installed local TESSA service via its authenticated named pipe.

**Not yet production-ready.** MJPEG is a temporary low-complexity streaming option. End-to-end Android↔Windows latency, microphone capture in background, STT and cross-network reliability have not been validated on a real handset. WebRTC video and end-to-end encryption are prerequisites for final acceptance.

## Windows setup

Python 3.12:
```
python -m pip install -r requirements.txt
python server.py --bind 127.0.0.1 --port 8766
```

The server generates a cryptographic 32-byte token and saves it in `%LOCALAPPDATA%/TESSA/mobile-bridge.token`. Do **not** commit it, email it or publish it. The Android app needs the server address and this token once in settings.

Bind to `127.0.0.1` for local tests. If testing on a trusted LAN or Tailscale, bind explicitly to the corresponding IP, allow only that private interface in Windows Firewall and set Android to `http://IP:8766`. Never expose the HTTP port on the public internet. Use HTTPS/WSS for any connection outside an already encrypted private overlay.

The remote microphone sends raw PCM at 16 kHz over WebSocket to the server, which can submit short WAV utterances to Groq Whisper if the local TESSA installation already has a Groq API key. TESSA authorization/confirmation rules remain intact. Android's mandatory microphone privacy indicator cannot be hidden.

## Design targets, not achieved claims

Frame pacing 10–20 fps MJPEG baseline, input acknowledgment, connection retry, touch coordinate mapping, TESSA status polling, foreground microphone service. Need real-device measurement before declaring performance.
