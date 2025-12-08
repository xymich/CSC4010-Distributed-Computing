# Slykord Viva Notes

Quick reference for implementation & design talking points.

## 1. High-Level Architecture
- Peer-to-peer room-based chat over UDP.
- Each node runs the same `ChatNode` middleware (CLI, GUI, HTTP API wrapper).
- Rooms: logical groups of peers; messages and peer lists are isolated per room.
- Key node: elected per room (oldest joiner); role only, no special hardware.

## 2. Key Node & Discovery
- Key node is responsible for room discovery broadcasts and history snapshots.
- If key node leaves (graceful or timeout), remaining nodes elect a new key.
- Election rule: oldest joinTime in that room (deterministic, no voting rounds).
- New key node starts broadcasting room info; discovery IP/port effectively shifts.

## 3. Send Message Pipeline (Middleware View)
- UI (CLI/GUI/HTTP) calls `ChatNode.sendChatMessage(text)`.
- Node increments Lamport clock (`incrementClock()`) to tag the event in logical time.
- Creates `ChatMessage` with a new UUID, sender id/nickname, content, and timestamp.
- Stores message in `messageHistory` and appends to a per-node log file under `logs/`.
- Wraps the `ChatMessage` in a `NetworkPacket` of type `CHAT_MESSAGE`.
- Serializes the packet; if above safe UDP size, fragmentation splits it into chunks.
- UDP handler broadcasts to same-room peers (and to key nodes in other swarms if applicable).
- Because it’s in `messageHistory`, the message will appear in `HISTORY_SNAPSHOT` for joiners and `/resync`.

## 4. History Sync (Join & /resync)
- On join or `/resync`, node calls a sync start method (e.g. `beginHistorySync(reason)`).
- Sets `historySyncInProgress = true` and `outboundMutedForSync = true` so user cannot send chat during sync.
- Sends a `JOIN_REQUEST` or `MESSAGE_SYNC` to a seed/peer to request peers + history.
- Peers respond with `PEER_LIST` and a `HISTORY_SNAPSHOT` containing past `ChatMessage`s and a cursor timestamp.
- While sync is active, incoming `CHAT_MESSAGE`s are deferred into a queue instead of applied immediately.
- Snapshot messages are merged into `messageHistory`, deduplicated by UUID, sorted by `(lamportTimestamp, messageId)`.
- After applying snapshot, node sets Lamport clock to `max(localClock, highestHistoryTimestamp) + 1`.
- `historySyncInProgress` is cleared, outbound is unmuted, and deferred chat packets are replayed in order.

## 5. UDP & Reliability Layer
- UDP chosen for low overhead and connectionless peer-to-peer communication (fits broadcast and many-to-many).
- Reliability is added at the application layer: ACK packets, history sync, and `/resync`.
- Each `CHAT_MESSAGE` is given a UUID and tracked in a pending set until at least one ACK is received.
- GUI shows pending messages in grey; first ACK recolours them white, CLI may show `[UNCONFIRMED]` on timeout.
- Large packets are fragmented into safe-sized UDP datagrams and reassembled by `FragmentAssembler` using packetId + sequence numbers.
- `/netloss N` randomly drops a percentage of inbound/outbound UDP packets to demonstrate behaviour under packet loss.

## 6. Seed Nodes & Manual Discovery
- A seed node is simply a node other peers can use as an initial contact point (usually the first node in a room).
- Starting as seed = do not join anyone, become initial key node and start broadcasting room discovery.
- `/seed <ip> <port>` adds a manual discovery target for networks where UDP broadcast is blocked.
- From a seed, new nodes learn: existing peers (via `PEER_LIST`), room info, and full history (via `HISTORY_SNAPSHOT`).

## 7. Key Node Election & Failover
- Each `PeerInfo` carries a `joinTime`; oldest joinTime in a room is elected as key node.
- Election is deterministic and local: all nodes can compute the same key without an explicit voting protocol.
- Key responsibilities: broadcast room discovery, provide history snapshots, coordinate cross-swarm key-node links.
- Heartbeats detect failure; if the key node times out, remaining peers remove it and elect the next-oldest node as new key.

## 8. Rooms, Isolation & Lifecycle
- Rooms are identified internally by a UUID/roomId and externally by a human-readable name (e.g. "Lobby").
- Messages and peer lists are scoped to a room; nodes in different rooms do not see each other's chat.
- Key node broadcasts `RoomInfo` via UDP to advertise a room on the subnet; `/rooms` shows discovered rooms.
- If the only node in a room (the key) leaves, the room effectively disappears from the network (no broadcaster left).
- If that node later restarts, it creates a new room instance (new roomId) even if the name is reused; it can still reload its own history from local logs.

## 9. HTTP External Interface (BroadcastService)
- `BroadcastService` runs its own `ChatNode` (e.g. nickname `BroadcastBot`) and joins a room like any other peer.
- Wraps that node with a minimal HTTP server using `HttpServer` exposing endpoints like `/message`, `/status`, `/peers`.
- `GET/POST /message` → parses text and calls `node.sendChatMessage(text)` so HTTP clients can inject chat messages.
- `/status` exposes node nickname, nodeId, room name, key-node flag, Lamport clock, and peer count as JSON.
- `/peers` returns JSON listing known peers (nickname, nodeId, IP:port, isKeyNode) for observability.
- This is a thin wrapper: it does not change the UDP protocol, only provides an easy external API surface for tools and scripts.

## 10. File Transfer (P2P over UDP)
- `/sendfile` (CLI) or GUI "Send File" reads a file into a byte array (up to a safety limit).
- Creates a `FileTransfer` object with UUID fileId, original name, size and raw bytes, wrapped in a `FILE_TRANSFER` packet.
- Uses the same fragmentation layer as messages to split large file payloads into UDP-safe chunks.
- Receivers de-duplicate files via a `receivedFileIds` set and save to disk under a downloads/logical directory.
- File transfer is broadcast P2P: no central server holds the file; every online peer in the room can receive it simultaneously.

## 11. Graceful vs Ungraceful Disconnect
- `/disconnect` sends a LEAVE/`LEAVE_NOTIFY` message so peers can immediately remove the node from their peer lists.
- Ungraceful exit (crash/Ctrl+C) is detected via missing heartbeats after a timeout (e.g. 30 seconds).
- In both cases, the network continues functioning; if the leaving node was key, failover election runs.
- Graceful disconnect is faster and cleaner for UX; ungraceful disconnect demonstrates resilience to failures.

## 12. Chat Rooms & Renaming
- `/newroom` creates a brand-new room for the node and leaves the previous swarm, resetting membership state.
- `/roomname <name>` (key node only) updates the human-readable name advertised in discovery, keeping the same internal roomId.
- Other nodes discover renamed rooms via updated `RoomInfo` broadcasts, but history and membership stay tied to roomId.

## 13. Robot Mode & Load Testing
- `/robot` toggles a background thread that periodically calls `sendChatMessage` with synthetic messages.
- Robot traffic goes through the same Lamport/UDP/ACK pipeline as user messages.
- Useful for demonstrating ordering, load, and behaviour under `/netloss` without manual typing.

---

## 14. Practice Q&A (Middleware Focus)

**Q1. Walk me through what happens when a user sends a message.**

- UI calls `sendChatMessage("Hello")` on the sender's `ChatNode`.
- Node increments its Lamport clock (`incrementClock()`), e.g. 10 → 11.
- Creates a `ChatMessage` with a fresh UUID `messageId`, sender `nodeId`/nickname, content, and `lamportTimestamp = 11`.
- Stores the message in `messageHistory` and appends it to the per-node log file under `logs/`.
- Wraps `ChatMessage` in a `NetworkPacket` of type `CHAT_MESSAGE` and serializes it.
- If the serialized packet is larger than the safe UDP payload, `PacketSerialiser` fragments it into smaller UDP packets with `packetId`, `seqNum`, and `totalFragments`.
- `UDPHandler` sends each fragment as a UDP datagram to all peers in the same room (and to key nodes in other swarms if needed).
- On a receiving node, `UDPHandler` collects fragments in `FragmentAssembler` until complete, then deserializes the full `NetworkPacket`.
- Receiver calls `updateClock(receivedTimestamp)` using the Lamport **max+1** rule, stores the new `ChatMessage` in `messageHistory`, and displays it in the UI.
- Receiver sends a `MESSAGE_ACK` back to the original sender with the `messageId`.
- Sender removes the id from its pending set and updates the UI: messages turn from grey (pending) to white (delivered) or lose `[UNCONFIRMED]` in CLI.

**Q2. Why use UDP instead of TCP, and how do you add reliability?**

- UDP is connectionless and lightweight: no per-peer connection setup/teardown, which suits a room-based P2P topology.
- It supports broadcast-style communication, ideal for discovery and fan-out to many peers without N TCP connections.
- A chat can tolerate some packet loss; if something important is missed, `/resync` and history snapshots can repair state.
- On top of UDP, the system adds:
	- Lamport clocks for logical ordering across nodes.
	- ACK packets for delivery feedback (grey → white messages, `[UNCONFIRMED]` if no ACK).
	- History sync and `/resync` to rebuild missing messages from peers.
	- Fragmentation/reassembly to safely handle larger messages and files.

**Q3. How does `/resync` work internally, and why mute outbound / defer inbound chat during sync?**

- `/resync` clears local `messageHistory` and invokes `beginHistorySync("manual resync")`.
- This sets `historySyncInProgress = true`, `outboundMutedForSync = true`, resets `highestHistoryTimestamp`, and clears deferred packet queues.
- Node sends a `MESSAGE_SYNC` request to peers asking for history; peers respond with `HISTORY_SNAPSHOT` packets.
- While syncing, incoming `CHAT_MESSAGE`s are **deferred** into a queue instead of being applied immediately, so the snapshot can be laid down first.
- Snapshot messages are merged into `messageHistory`, deduplicated by UUID, and sorted by `(lamportTimestamp, messageId)` to get a stable total order.
- After applying snapshots, node sets its Lamport clock to `max(localClock, highestHistoryTimestamp) + 1` so new messages are strictly after all history.
- `historySyncInProgress` is cleared, outbound chat is unmuted, and deferred live chat packets are processed in timestamp order.
- Muting outbound ensures the user doesn’t send replies before they see full history; deferring inbound avoids new messages appearing before older historical ones.

**Q4. Explain graceful vs ungraceful disconnect.**

- **Graceful** (`/disconnect` or clean shutdown): node sends a `LEAVE_NOTIFY` packet so peers immediately remove it from their peer lists.
- If the leaving node was key, peers promptly run key-node election and promote the next-oldest joiner.
- **Ungraceful** (crash, `Ctrl+C`, power loss): no leave packet is sent; peers detect failure via missing heartbeats.
- Each peer tracks `lastSeen` and runs a timeout check; if `now - lastSeen > PEER_TIMEOUT_MS` (~30s), it logs a timeout, removes the peer, and if necessary triggers key election.
- In both cases the network self-heals; graceful just gives faster, cleaner feedback to users.

**Q5. How is the key node chosen and what happens if it crashes?**

- Every `PeerInfo` records a `joinTime` for that room; the peer with the earliest `joinTime` is elected key node.
- This rule is simple, deterministic, and uses existing data—every node can compute the same result with no extra voting protocol.
- Key node responsibilities include broadcasting room discovery and serving history snapshots.
- If the key node crashes, its heartbeats stop; after the timeout, peers mark it dead and remove it.
- The remaining peers run the same oldest-joinTime rule to pick a new key node, which then starts room discovery broadcasts and history duties.

**Q6. How are names allocated to nodes and rooms, and why separate human names from IDs?**

- Nodes:
	- Machine identity: a UUID `nodeId` generated locally with `UUID.randomUUID()`.
	- Human identity: a user-chosen nickname stored in `ChatNode`/`PeerInfo` and shown in UIs.
	- Internally, UUIDs are used for deduplication, maps, Lamport tie-breaking; externally, nicknames keep the UI readable.
- Rooms:
	- Internal ID: `roomId` (UUID) plus `swarmId` derived from it for grouping.
	- Human name: string such as `"Lobby"`, stored in `ChatNode`/`RoomInfo` and advertised in discovery.
	- `/roomname <name>` (key node only) updates the advertised name but keeps the same `roomId`, so history and membership remain attached.

**Q7. What happens if the only node in a room (the key) disconnects and later comes back?**

- While it is running, the key node is the sole broadcaster and history holder for that room.
- If it leaves and there are no other members, the room effectively disappears from the network—no node is left to advertise or serve it.
- When it later restarts, it creates a new `ChatNode` with a new `roomId` (even if the user reuses the same room name).
- To the network this is a **new room instance**; to the user it can look similar because the name is the same and local logs can reload old messages.

**Q8. What does the HTTP external interface (`BroadcastService`) add on top of the chat node?**

- `BroadcastService` spins up a normal `ChatNode` (e.g. `BroadcastBot`) and joins a room like any other peer.
- It attaches a small `HttpServer` exposing endpoints like `/message`, `/status`, and `/peers`.
- `GET/POST /message` takes text from a query/body and calls `node.sendChatMessage(text)`, so scripts, CI pipelines, or dashboards can inject messages via HTTP.
- `/status` exposes JSON with nickname, nodeId, room, key-node flag, Lamport clock, and peer count; `/peers` lists peers with addresses and key-node flags.
- The HTTP layer does not change the underlying UDP protocol; it is just a thin, language-agnostic façade for external tools.
