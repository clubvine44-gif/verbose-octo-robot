#!/usr/bin/env bash
set -euo pipefail

# Run as root on the Linux relay host.
# The script configures the fixed v0.2 Android tunnel address.
TUN="${1:-mayak0}"
UPLINK="${2:-eth0}"

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
