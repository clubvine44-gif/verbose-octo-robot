# MAYAK Pulse

Pulse is the control plane for MAYAK. It does not carry user traffic. It publishes a small directory of MAYAK gateways so the Android client can select an available egress automatically.

## Directory contract

`directory.json` contains:

- `version` — directory format version.
- `generated_at` — optional UTC generation timestamp.
- `nodes` — public gateway metadata.

Each node may contain:

- `id` — stable node identifier.
- `country` — egress country code.
- `endpoint` — HTTPS/TLS gateway hostname.
- `port` — gateway port.
- `transport` — transport name (`tls` for the current engine).
- `enabled` — whether the node is eligible for selection.
- `weight` — optional relative preference.

Secrets are deliberately not stored in the directory. Authentication material remains device-side or is provisioned by a trusted deployment flow.

## Design rule

Pulse must remain disposable. The Android client should continue to work with a cached directory if Pulse is temporarily unavailable, and a missing/invalid directory must never cause arbitrary traffic to an untrusted endpoint.
