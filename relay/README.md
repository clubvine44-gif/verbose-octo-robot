# MAYAK Relay

Server-side component for MAYAK Transport Engine v0.2.

## Current status

The relay now implements the complete **IPv4 packet bridge** between the Android TUN and a Linux server TUN over TLS:

```text
Android TUN (10.7.0.2)
   -> TrafficRouter
   -> MAYAK frame
   -> TLS 1.2/1.3
   -> Linux relay
   -> mayak0 (10.7.0.1/30)
   -> IP forwarding + NAT
   -> Internet
   -> mayak0
   -> TLS
   -> Android TUN
```

v0.2 intentionally supports **one active client** because the Android client uses the fixed tunnel address `10.7.0.2`. IPv6 packets are rejected by the Android router and the relay accepts only IPv4 payloads.

The relay validates the MAYAK magic, protocol version, frame length and packet IP version. TLS handshakes have an explicit timeout; TLS certificate verification remains the responsibility of the Android client and must not be disabled.

## Build

Requires Go 1.23+.

```bash
go test ./...
go build -o mayak-relay .
```

## Linux prerequisites

The relay host needs:

- Linux with `/dev/net/tun`;
- permission to create/configure TUN (`root` or appropriate network capabilities);
- `ip`, `sysctl` and `iptables` for the included setup script;
- an Internet-facing TCP port reachable by the Android client;
- a valid TLS certificate and private key.

## Configure networking

From the `relay` directory, run as root:

```bash
./setup-linux.sh
```

The script automatically detects the default Internet uplink. To specify it explicitly:

```bash
./setup-linux.sh mayak0 eth0
```

It creates/configures `mayak0`, assigns `10.7.0.1/30`, enables IPv4 forwarding, and installs the required MASQUERADE/FORWARD rules for `10.7.0.2`.

## Run manually

The relay expects a certificate and private key:

```bash
./mayak-relay -listen :443 -cert server.crt -key server.key -tun mayak0
```

Do not disable TLS certificate or hostname validation on the Android client. Use a certificate whose hostname matches the configured relay, or implement explicit certificate pinning.

## Install as systemd service

Build the binary first:

```bash
go build -o mayak-relay .
```

Then, as root:

```bash
./install.sh
```

The installer places the binary in `/opt/mayak-relay`, installs the systemd unit, configures TUN/NAT, and enables the service. Before starting it, place the TLS files at:

```text
/etc/mayak/server.crt
/etc/mayak/server.key
```

The private key should be readable only by root (`chmod 600 /etc/mayak/server.key`).

Start/check the service with:

```bash
systemctl start mayak-relay
systemctl status mayak-relay
journalctl -u mayak-relay -f
```

## Security and deployment limits

- v0.2 has no application-level client authentication; possession of a valid TLS connection is sufficient to reach the tunnel.
- Only one active client is supported.
- IPv6 is intentionally unsupported.
- The included firewall setup assumes an `iptables` environment; systems managed exclusively through nftables may require equivalent native rules.
- Full Android-to-Internet end-to-end connectivity still requires a real Linux VPS/server test. The repository unit tests do not prove Internet forwarding.
