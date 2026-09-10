#!/usr/bin/env bash
set -euo pipefail

# Install the already-built MAYAK relay on a Linux server.
# TLS certificate/key are intentionally not generated or copied by this script.
# Place a certificate matching the relay hostname at /etc/mayak/server.crt and
# its private key at /etc/mayak/server.key before starting the service.

if [[ "$(id -u)" -ne 0 ]]; then
  echo "error: run as root" >&2
  exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BINARY="${1:-$SCRIPT_DIR/mayak-relay}"
INSTALL_DIR="/opt/mayak-relay"
CONFIG_DIR="/etc/mayak"
TOKEN_FILE="$CONFIG_DIR/access.token"

[[ -x "$BINARY" ]] || { echo "error: relay binary not found or not executable: $BINARY" >&2; exit 1; }
[[ -f "$SCRIPT_DIR/setup-linux.sh" ]] || { echo "error: setup-linux.sh not found next to installer" >&2; exit 1; }
command -v openssl >/dev/null || { echo "error: openssl is required to generate the access token" >&2; exit 1; }

install -d -m 0755 "$INSTALL_DIR" "$CONFIG_DIR"
install -m 0755 "$BINARY" "$INSTALL_DIR/mayak-relay"
install -m 0755 "$SCRIPT_DIR/setup-linux.sh" "$INSTALL_DIR/setup-linux.sh"
install -m 0644 "$SCRIPT_DIR/mayak-relay.service" /etc/systemd/system/mayak-relay.service

if [[ ! -s "$TOKEN_FILE" ]]; then
  umask 077
  openssl rand -hex 32 > "$TOKEN_FILE"
  chmod 0600 "$TOKEN_FILE"
  echo "Generated a new MAYAK relay access token."
  echo "Enter this token in the Android app:"
  cat "$TOKEN_FILE"
fi

# Configure the TUN, forwarding and NAT before enabling the relay service.
"$INSTALL_DIR/setup-linux.sh" mayak0 "${UPLINK:-}"

systemctl daemon-reload
systemctl enable mayak-relay

if [[ ! -f "$CONFIG_DIR/server.crt" || ! -f "$CONFIG_DIR/server.key" ]]; then
  echo "MAYAK relay installed, but TLS certificate/key are missing."
  echo "Expected: $CONFIG_DIR/server.crt and $CONFIG_DIR/server.key"
  echo "Install a valid certificate for the relay hostname, then run:"
  echo "  systemctl start mayak-relay"
  exit 0
fi

systemctl restart mayak-relay
systemctl --no-pager --full status mayak-relay || true
