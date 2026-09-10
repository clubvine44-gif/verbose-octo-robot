# MAYAK Relay

Server-side component for MAYAK Transport Engine v0.2.

## Current status

This first relay milestone provides a TLS listener and a compatible MAYAK frame decoder. It intentionally does **not** claim Internet forwarding yet.

```text
Android TUN
   -> MAYAK frame
   -> TLS
   -> relay
   -> frame decoder
   -> [server TUN + routing/NAT: next milestone]
   -> Internet
```

The relay accepts TLS 1.2+ and validates the MAYAK magic, protocol version, frame type and maximum payload size.

## Build

Requires Go 1.23+.

```bash
go build ./...
```

## Run

The relay expects a certificate and private key:

```bash
./mayak-relay -listen :4433 -cert server.crt -key server.key
```

Do not disable TLS certificate validation on the Android client. For production, use a certificate whose hostname matches the relay or configure explicit certificate pinning.

## Next milestone: server TUN

The next step is a Linux-only forwarding path:

1. create `/dev/net/tun` interface;
2. assign a private tunnel address;
3. write received IPv4 packets from MAYAK frames into TUN;
4. read return packets from TUN;
5. send return packets as MAYAK `TYPE_IP` frames;
6. enable IP forwarding and NAT on the server firewall;
7. integration-test Android TUN -> relay TUN -> Internet -> relay TUN -> Android TUN.

The relay must run with the privileges/capabilities required to create and configure the TUN interface and forwarding rules.
