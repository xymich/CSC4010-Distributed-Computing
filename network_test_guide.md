# Network Testing Guide

## Compile All Files

```bash
javac *.java
```

## Test 1: Two Nodes on Same Machine

### Terminal 1 (Seed Node - Alice)
```bash
java SimpleChatClient
```
- Nickname: `Alice`
- Port: `5000`
- Join network: `n`

### Terminal 2 (Join Node - Bob)
```bash
java SimpleChatClient
```
- Nickname: `Bob`
- Port: `5001`
- Join network: `y`
- Peer IP: `127.0.0.1`
- Peer port: `5000`

### Expected Results:
- Bob should receive all of Alice's messages (if any)
- Alice should see "Join request from: Bob"
- Both can send messages and see each other's messages
- `/peers` command shows the other node
- Messages appear with Lamport timestamps

---

## Test 2: Three Nodes

### Terminal 1 (Alice - Seed)
```bash
java SimpleChatClient
```
- Nickname: `Alice`
- Port: `5000`
- Join network: `n`

### Terminal 2 (Bob)
```bash
java SimpleChatClient
```
- Nickname: `Bob`
- Port: `5001`
- Join network: `y`
- Connect to: `127.0.0.1:5000`

### Terminal 3 (Charlie)
```bash
java SimpleChatClient
```
- Nickname: `Charlie`
- Port: `5002`
- Join network: `y`
- Connect to: `127.0.0.1:5000` (or `127.0.0.1:5001`)

### Expected Results:
- All three nodes see each other in `/peers`
- Messages from any node appear on all nodes
- `/messages` shows same messages (in same order due to Lamport timestamps)

---

## Test 3: Different Machines (Same Network)

### Machine 1 (Seed Node)
```bash
java SimpleChatClient
```
- Nickname: `Alice`
- Port: `5000`
- Join network: `n`
- **Note your IP address** (use `ipconfig` on Windows or `ifconfig`/`ip addr` on Linux/Mac)

### Machine 2
```bash
java SimpleChatClient
```
- Nickname: `Bob`
- Port: `5000` (can be same port since different machine)
- Join network: `y`
- Peer IP: `<Machine 1's IP address>` (e.g., `192.168.1.100`)
- Peer port: `5000`

### Expected Results:
- Nodes on different machines can communicate
- Messages propagate across network
- Check firewall settings if connection fails

---

## Commands to Try

1. **Send a message**: Just type and press Enter
   ```
   Hello everyone!
   ```

2. **View peers**: 
   ```
   /peers
   ```

3. **View message history**:
   ```
   /messages
   ```

4. **Check node status**:
   ```
   /status
   ```

5. **Graceful exit**:
   ```
   /quit
   ```

---

## What to Observe

✓ **Message Propagation**: Messages appear on all connected nodes
✓ **Ordering**: Messages display in Lamport timestamp order
✓ **Deduplication**: Same message doesn't appear twice
✓ **Join Sync**: New nodes receive full chat history
✓ **Peer Discovery**: Nodes learn about all other nodes
✓ **Graceful Leave**: `/quit` notifies other nodes

---

## Troubleshooting

**Messages not appearing?**
- Check firewall settings
- Verify IP addresses are correct
- Ensure ports are not already in use
- Check if nodes are on same network

**"Address already in use" error?**
- Use different port numbers
- Or wait 30 seconds for port to free up

**Nodes can't find each other across machines?**
- Verify machines are on same network
- Check firewall allows UDP on your port
- Ping the other machine first to verify connectivity
- Try disabling firewall temporarily for testing

---

## Next Steps

Once basic networking works:
1. Test with 4+ nodes
2. Test message ordering with rapid sends
3. Test node crash (Ctrl+C) vs graceful exit (/quit)
4. Implement swarm partitioning logic
5. Add heartbeat monitoring for failed nodes
