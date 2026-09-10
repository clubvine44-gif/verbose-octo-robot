# MAYAK Relay

Server-side component for MAYAK Transport Engine v0.2.

## Current status

The relay implements the **IPv4 packet bridge** between the Android TUN and a Linux server TUN over TLS, with an application-level access token checked before IP traffic is accepted:

```text
Android TUN (10.7.0.2)
   -> TrafficRouter
   -> MAYAK AUTH frame
   -> TLS 1.2/1.3
   -> access-token verification
   -> MAYAK IP frames
   -> Linux relay
   -> mayak0 (10.7.0.1/30)
   -> IP forwarding + NAT
   -> Internet
   -> mayak0
   -> TLS
   -> Android TUN
```

v0.2 intentionally supports **one active client** because the Android client uses the fixed tunnel address `10.7.0.2`. IPv6 packets are rejected by the Android router and the relay accepts only IPv4 payloads.

The relay validates the MAYAK magic, protocol version, frame length and packet IP version. TLS handshakes have an explicit timeout; TLS certificate and hostname verification remain the responsibility of the Android client and must not be disabled.

## Build

Requires Go 1.23+.

```bash
go test ./...
go vet ./...
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

## Access token

The relay requires an access token in addition to TLS. The token is loaded from a root-readable file and compared in constant time.

The installer generates a random 256-bit token automatically when `/etc/mayak/access.token` does not exist:

```text
/etc/mayak/access.token
```

Keep this value secret. Enter the generated value into the **Relay access token** field in the Android application. Never commit the token to Git.

## Run manually

The relay expects a certificate, private key and token file:

```bash
./mayak-relay -listen :443 -cert server.crt -key server.key -auth-token-file access.token -tun mayak0
```

Use a certificate whose hostname matches the configured relay. Do not disable TLS certificate or hostname validation on Android.

## Install as systemd service

Build the binary first:

```bash
go build -o mayak-relay .
```

Then, as root:

```bash
./install.sh
```

The installer places the binary in `/opt/mayak-relay`, installs the systemd unit, generates `/etc/mayak/access.token` if needed, configures TUN/NAT, and enables the service. Before starting it, place the TLS files at:

```text
/etc/mayak/server.crt
/etc/mayak/server.key
```

Protect the private key and token:

```bash
chmod 600 /etc/mayak/server.key /etc/mayak/access.token
```

Start/check the service with:

```bash
systemctl start mayak-relay
systemctl status mayak-relay
journalctl -u mayak-relay -f
```

## Security and deployment limits

- TLS certificate/hostname verification must remain enabled on Android.
- The access token is transmitted only inside the established TLS session.
- Only one active client is supported.
- IPv6 is intentionally unsupported.
- The included firewall setup assumes an `iptables` environment; systems managed exclusively through native nftables may require equivalent rules.
- Full Android-to-Internet end-to-end connectivity still requires a real authorized Linux VPS/server test. Repository unit tests do not prove Internet forwarding.
