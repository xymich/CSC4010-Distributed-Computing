# Slykord (Slack, Skype & Discord) - a P2P Distributed Chat System

Leader-elected, room-based peer-to-peer chat over UDP, with both CLI and Swing GUI front-ends. Nodes discover rooms and peers via UDP broadcast, elect a key node per room, and exchange messages, history, and files without any central server.

---

## 1. Build and Run

### Prerequisites
- Java 17+ (tested with recent OpenJDK)
- UDP allowed on your local network (for discovery)

### Compile

```bash
javac -d bin *.java
```

### Launch

```bash
cd bin
java ChatLauncher
```

You will be prompted for:
- Interface: `1` = GUI, `2` = CLI
- Nickname
- Local UDP port (e.g. 1111, 2222, 3333)
- Whether to join an existing network or start as a new seed node

For a quick multi-node demo, open 2–3 terminals or machines and start `ChatLauncher` on each with different ports.

---

## 2. High-Level Features

- **Leader‑elected room-based P2P**: Each chat room elects a key node (oldest member) to handle discovery broadcasts and history for joiners. Leadership is a role; any node can become key.
- **Multiple rooms**: Create, discover, join, and rename rooms. Messages are isolated per room.
- **Robust messaging**:
  - Lamport logical clocks for consistent global ordering across nodes.
  - Unique UUIDs for nodes and messages to avoid duplicates.
  - Delivery acknowledgements: GUI shows pending vs delivered; CLI shows unconfirmed messages.
- **History sync & recovery**:
  - New node receives a full history snapshot on join.
  - `/resync` rebuilds history from peers.
  - `/messages` redraws chat from local stored history.
  - Local log-based redraw is available via GUI and CLI helpers.
- **Discovery**:
  - Room discovery via UDP broadcast on a port range (key nodes only).
  - Peer discovery via peer list exchange and heartbeats.
  - Manual seeding with `/seed` for restrictive networks.
- **File transfer**:
  - `/sendfile` command (and GUI button) sends binary files P2P over UDP.
  - Large payloads are fragmented and reassembled safely.
- **Network simulation**:
  - `/netloss` introduces configurable inbound/outbound packet loss.
  - Demonstrates message loss, timeouts, and recovery under stress.
- **Resilience**:
  - Heartbeats and timeouts detect dead peers.
  - Automatic key node failover when the current key leaves or crashes.
- **GUI client**:
  - Room list, peer list, key node markers.
  - Message colouring for delivery status.
  - Buttons for file send, resync, and local log load.

---

## 3. CLI Command Reference

### Messaging & Info
- `Just type + Enter` – Send a chat message
- `/status` – Show node info (UUID, port, Lamport clock)
- `/peers` – List connected peers in the current room
- `/messages` – Display message history from local storage
- `/rooms` – List discovered chat rooms

### Room Management
- `/join <number>` – Join a discovered room by index
- `/newroom` – Create a new room
- `/roomname <name>` – Rename current room (key node only)

### Network Control
- `/disconnect` – Gracefully disconnect from the network
- `/reconnect` – Reconnect to the last room/network
- `/seed <ip> <port>` – Manually add a discovery target
- `/seeds` – List configured discovery targets

### Testing & Simulation
- `/robot` – Toggle automated robot chat messages
- `/netloss <0-100>` – Simulate packet loss percentage
- `/resync` – Rebuild history from peers
- `/sendfile <path>` – Send a file to all peers in the room
- `/clear` – Clear the chat display (history in memory is untouched)

### Other
- `/help` – Show CLI command help
- `/quit` – Exit the application

Some features (like changing identity) are exposed in the GUI (`Logout` button) rather than as CLI commands.

---

## 4. Architecture Overview

- **Transport**: All communication uses UDP datagrams. A custom `NetworkPacket` format is serialised and deserialised in `PacketSerialiser`, with fragmentation for large payloads.
- **Leader election**: Inside a room, the oldest node (by join time) becomes the key node and is responsible for discovery broadcasts and providing history snapshots.
- **State & ordering**:
  - `ChatNode` tracks peers, rooms, Lamport clock, and message history.
  - Lamport clock is advanced on send and updated on receive, with UUID tie-breaking for deterministic ordering.
- **Discovery**:
  - `DiscoveryHandler` periodically broadcasts room info from key nodes and listens for broadcasts to populate the room list.
  - Manual seeding lets you bootstrap discovery without broadcast.
- **Reliability**:
  - Delivery acknowledgements (`MESSAGE_ACK`) update GUI/CLI status.
  - Heartbeats and timeouts remove dead peers and trigger key node re‑election.
- **History management**:
  - History sync uses `MESSAGE_SYNC` and `HISTORY_SNAPSHOT` messages.
  - Local logs allow reconstructing the chat view even without peers.
- **File transfer**:
  - `FileTransfer` payloads are sent as fragmented UDP packets and reassembled by `FragmentAssembler`.
