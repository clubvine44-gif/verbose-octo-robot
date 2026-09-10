# MAYAK

Experimental Android traffic-routing client. Transport Engine v0.2 connects the Android TUN interface to a dedicated MAYAK relay over a TLS-protected, length-delimited transport.

## Architecture

```text
Android VpnService / TUN
        |
        v
TrafficRouter (IPv4)
        |
        v
MAYAK framing
        |
        v
TLS 1.3 / 1.2 transport
        |
        v
MAYAK relay
        |
        v
Internet
```

## v0.2 status

- Android `VpnService` TUN capture
- IPv4 packet classification
- fail-closed handling for unsupported IPv6
- MAYAK frame header: magic + version + type + length
- TLS 1.3/1.2 transport using the platform trust store
- `VpnService.protect(Socket)` for the relay connection so it is not captured by the same VPN
- relay host/port configuration in the app
- packet receive path from relay back into TUN
- GitHub Actions Android debug build on feature branches

The transport deliberately uses TLS instead of custom cryptography. MAYAK owns the framing and routing protocol; TLS supplies the authenticated encrypted channel. Rolling a new cryptographic primitive would add risk without improving the transport contract.

## Important limitation

The Android side is now wired for a real relay, but this repository does **not yet claim end-to-end Internet connectivity**. A compatible MAYAK relay must terminate the frame protocol and forward packets through a server-side network interface with routing/NAT. Until that relay exists, the client fails closed rather than falling back to the normal network.

IPv6 is explicitly rejected in v0.2 and is not silently sent outside MAYAK.
