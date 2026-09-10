#!/usr/bin/env bash
set -euo pipefail

# Run as root on the Linux relay host.
# Usage: ./setup-linux.sh [tun_name] [uplink]
TUN="${1:-mayak0}"
UPLINK="${2:-}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "error: run as root" >&2
  exit 1
fi

command -v ip >/dev/null || { echo "error: ip command not found" >&2; exit 1; }
command -v sysctl >/dev/null || { echo "error: sysctl command not found" >&2; exit 1; }
command -v iptables >/dev/null || { echo "error: iptables not found; install iptables or provide an equivalent firewall configuration" >&2; exit 1; }

if [[ -z "$UPLINK" ]]; then
  UPLINK="$(ip route get 1.1.1.1 | awk '/dev/ {for (i=1; i<=NF; i++) if ($i=="dev") {print $(i+1); exit}}')"
fi
[[ -n "$UPLINK" ]] || { echo "error: could not detect Internet uplink; pass it as the second argument" >&2; exit 1; }
ip link show "$UPLINK" >/dev/null 2>&1 || { echo "error: uplink interface '$UPLINK' does not exist" >&2; exit 1; }

ip link show "$TUN" >/dev/null 2>&1 || ip tuntap add dev "$TUN" mode tun
ip addr replace 10.7.0.1/30 dev "$TUN"
ip link set "$TUN" up

# Enable forwarding for the tunnel's IPv4 traffic.
sysctl -w net.ipv4.ip_forward=1 >/dev/null

# NAT packets originating from the Android tunnel address out through the uplink.
iptables -t nat -C POSTROUTING -s 10.7.0.2/32 -o "$UPLINK" -j MASQUERADE 2>/dev/null || \
  iptables -t nat -A POSTROUTING -s 10.7.0.2/32 -o "$UPLINK" -j MASQUERADE

iptables -C FORWARD -i "$TUN" -o "$UPLINK" -s 10.7.0.2/32 -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -i "$TUN" -o "$UPLINK" -s 10.7.0.2/32 -j ACCEPT
iptables -C FORWARD -i "$UPLINK" -o "$TUN" -d 10.7.0.2/32 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -i "$UPLINK" -o "$TUN" -d 10.7.0.2/32 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

printf 'MAYAK TUN %s configured on %s\n' "$TUN" "$UPLINK"
printf 'Tunnel address: 10.7.0.1/30; Android peer: 10.7.0.2\n'
printf 'IPv4 forwarding: enabled; NAT: MASQUERADE\n'
