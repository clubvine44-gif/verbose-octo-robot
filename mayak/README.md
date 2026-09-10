# MAYAK

Experimental Android traffic-routing client. v0.1 is a local TUN/VpnService prototype with a diagnostics-first UI. It does not impersonate or use third-party infrastructure as a disguise.

## Status
- Android client skeleton
- Local TUN interface
- Packet counters
- Start/stop controls
- Static landing page
- GitHub Actions Android build

Android `VpnService` is used only as the OS-provided packet interception mechanism. See the official Android documentation for the API contract.
