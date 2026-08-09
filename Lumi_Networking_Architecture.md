# Lumi Networking Architecture

## Objective

Build a low-latency, free-to-use networking layer for synchronizing media playback between Lumi devices.

Lumi does not stream or transfer media. Only playback commands and synchronization state are transmitted.

## Core Principle

Playback traffic should travel directly between devices whenever possible.

The backend must never be the normal path for PLAY, PAUSE, SEEK, playback state, heartbeats, or position synchronization.

A coordination/discovery service may exist later, but it should only help devices find/connect to each other.

## Phase 1 — Development Network

Use:

- Tailscale
- Direct peer-to-peer connectivity
- UDP-based Lumi protocol

```text
Device A
   │
   │ Direct encrypted connection
   ▼
Device B
```

Tailscale is only the network substrate. Lumi must not depend on the Tailscale API.

## Phase 2 — Native Lumi Networking

Eventually replace the Tailscale dependency with Lumi's own discovery/NAT-traversal mechanism.

Candidates:

- STUN
- ICE
- QUIC
- UDP
- WebRTC DataChannel

Choose after measuring Phase 1.

## Network Topology

The host is a connection coordinator, not a playback authority.

```text
              Host
             /    \
            /      \
         Peer A   Peer B
```

Every connected device can control playback.

The host only forwards synchronization events in the initial topology.

## Heartbeat

Do not use a backend database for heartbeat propagation.

Use direct network heartbeats every 2–5 seconds:

```text
PING → PONG
```

Measure RTT and use it for synchronization calculations.

## Playback Events

Each event contains:

- Device ID
- Event ID
- Timestamp
- Event type
- Playback position

Supported events:

```text
PLAY
PAUSE
SEEK
```

Example:

```json
{
  "type": "PLAY",
  "device_id": "device-a",
  "event_id": "01HX...",
  "timestamp": 1754670000123,
  "position": 3821.42
}
```

## Everyone Can Control Playback

There is no permanent playback master.

Any connected device may issue PLAY, PAUSE, or SEEK.

The event is distributed to all peers.

## Conflict Resolution

If two devices issue commands at approximately the same time, the newest valid event wins.

Every event contains:

```text
timestamp
device_id
event_id
```

Device clocks must not be blindly trusted for production conflict resolution. Investigate logical timestamps or synchronized time later.

## Playback Synchronization

Do not continuously seek every second.

A PLAY event contains the position at which playback began:

```text
PLAY @ 123.45s
```

The receiver calculates its expected position from:

```text
event position + elapsed time
```

This avoids unnecessary seeking.

## Drift Correction

Periodically exchange playback state.

```json
{
  "type": "STATE",
  "position": 3822.31,
  "playing": true,
  "timestamp": 1754670000450
}
```

If drift is below approximately 250 ms, do nothing.

If drift exceeds the threshold, correct playback position.

The threshold must be experimentally tuned.

## Connection Loss

If a peer disconnects:

- Do not alter local playback.
- Mark the peer disconnected.
- Continue local playback.
- Attempt reconnection.

If the host disconnects:

- Detect failure through heartbeat timeout.
- Host migration is a future feature, not part of the first prototype.

## Transport Evaluation

### UDP

Very low overhead and suitable for small synchronization packets.

Reliability and NAT traversal must be handled separately.

### QUIC

Candidate production transport because it provides UDP-based encrypted connections, streams, and connection management.

### WebRTC DataChannel

Candidate production transport because it provides P2P connectivity, ICE/STUN traversal, encryption, and data channels.

### WebSocket/TCP

Useful for early debugging but not the preferred latency-sensitive transport.

## Recommended Development Path

### Stage 1

```text
Rust
+
Tailscale
+
UDP
```

No backend.

Prove:

```text
Device A → PLAY → Device B
Device A → PAUSE → Device B
Device A → SEEK → Device B
```

### Stage 2

Measure:

- RTT
- Packet loss
- Playback drift
- Seek correction latency
- Connection recovery

Test:

- Same LAN
- Different networks
- Direct Tailscale connection
- Tailscale relay connection

### Stage 3

Compare:

```text
Raw UDP
vs
QUIC
vs
WebRTC DataChannel
```

Choose based on measured performance and implementation complexity.

### Stage 4

Implement Lumi's own discovery/NAT traversal layer.

The synchronization engine must remain independent of the transport.

## Software Architecture

```text
Lumi Core
│
├── Media Adapter
├── Synchronization Engine
├── Network Transport
├── Discovery
└── Protocol
```

### Media Adapter

Communicates with the operating system.

### Synchronization Engine

Handles:

- Playback events
- Event ordering
- Drift correction
- Conflict resolution

### Network Transport

Handles:

- Connections
- Packets
- Sending
- Receiving
- Heartbeats

### Discovery

Finds peers and establishes connectivity.

### Protocol

Defines messages exchanged between devices.

## Constraints

Do NOT:

- Send movie files
- Stream movie data
- Store playback events in a cloud database
- Make the backend part of the playback path
- Build a media player
- Add UI before the media/network architecture works

## Final Target

```text
              Discovery / Coordination
                       │
                       │ Connection Setup
                       ▼

        ┌──────────────────────────────┐
        │       Direct P2P Channel     │
        │          UDP / QUIC          │
        └──────────────────────────────┘
                  │             │
                  ▼             ▼
               Lumi A        Lumi B
                  │             │
                  ▼             ▼
            Media Adapter  Media Adapter
                  │             │
                  ▼             ▼
              OS Player      OS Player
```

The network layer must be replaceable without changing the synchronization engine.

The media layer must be replaceable without changing the networking engine.
