import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ChatNode {
    
    // Node identity
    private UUID nodeId;
    private String nickname;
    private String ipAddress;
    private int port;
    
    // Room identity
    private UUID roomId;
    private String roomName;
    
    // Swarm management
    private int swarmId;
    private boolean isSwarmKey;
    
    // Lamport clock
    private AtomicLong lamportClock;
    
    // Peer management - using thread-safe collections
    private Map<UUID, PeerInfo> knownPeers;
    private Set<UUID> swarmPeers;      // Peers in same swarm
    private Set<UUID> keyNodePeers;    // Key nodes from other swarms
    
    // Message storage - using thread-safe collection
    private Map<UUID, ChatMessage> messageHistory;
    
    // Network components
    private UDPHandler udpHandler;
    private FragmentAssembler fragmentAssembler;
    private DiscoveryHandler discoveryHandler;
    private SwarmManager swarmManager;
    private boolean running;
    
    // Join time tracking
    private long joinTime;
    
    public ChatNode(String nickname, String ipAddress, int port) {
        this(nickname, ipAddress, port, "General Chat");
    }
    
    public ChatNode(String nickname, String ipAddress, int port, String roomName) {
        this.nodeId = UUID.randomUUID();
        this.nickname = nickname;
        this.ipAddress = ipAddress;
        this.port = port;
        
        this.roomId = UUID.randomUUID();
        this.roomName = roomName;
        
        this.swarmId = 0;  // Default swarm
        this.isSwarmKey = false;
        
        this.lamportClock = new AtomicLong(0);
        
        this.knownPeers = new ConcurrentHashMap<>();
        this.swarmPeers = ConcurrentHashMap.newKeySet();
        this.keyNodePeers = ConcurrentHashMap.newKeySet();
        this.messageHistory = new ConcurrentHashMap<>();
        
        this.fragmentAssembler = new FragmentAssembler();
        this.running = false;
        
        // Initialize join time and swarm manager
        this.joinTime = System.currentTimeMillis();
        this.swarmManager = new SwarmManager(this);
        
        // Don't create UDP handler in constructor - do it in start() instead
        // This prevents port binding issues when constructor fails
        this.udpHandler = null;
        
        this.discoveryHandler = new DiscoveryHandler(
            roomId, 
            roomName, 
            ipAddress, 
            port, 
            () -> knownPeers.size() + 1 // +1 for self
        );
        discoveryHandler.setBroadcastEnabled(false); // enable once we become an active room
        knownPeers.put(nodeId, createOwnPeerInfo());
    }
    
    // Lamport clock operations
    public long incrementClock() {
        return lamportClock.incrementAndGet();
    }
    
    public void updateClock(long receivedTimestamp) {
        long current = lamportClock.get();
        long newValue = Math.max(current, receivedTimestamp) + 1;
        lamportClock.set(newValue);
    }
    
    public long getClock() {
        return lamportClock.get();
    }
    
    // Getters
    public UUID getNodeId() { return nodeId; }
    public String getNickname() { return nickname; }
    public String getIpAddress() { return ipAddress; }
    public int getPort() { return port; }
    public UUID getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public int getSwarmId() { return swarmId; }
    public boolean isSwarmKey() { return isSwarmKey; }
    
    // Setters
    public void setSwarmId(int swarmId) { 
        this.swarmId = swarmId; 
        syncSelfPeerInfo();
    }
    public void setSwarmKey(boolean isSwarmKey) { 
        this.isSwarmKey = isSwarmKey; 
        syncSelfPeerInfo();
        setDiscoveryBroadcastEnabled(isSwarmKey);
        if (isSwarmKey) {
            triggerDiscoveryBroadcast();
        }
    }
    public void setRoomName(String roomName) { 
        this.roomName = roomName;
        if (discoveryHandler != null) {
            discoveryHandler.setRoomName(roomName);
        }
    }
    
    // Peer management methods
    public void addPeer(PeerInfo peer) {
        if (peer.getNodeId().equals(nodeId)) {
            syncSelfPeerInfo();
            return;
        }
        knownPeers.put(peer.getNodeId(), peer);
        if (peer.getSwarmId() == this.swarmId) {
            swarmPeers.add(peer.getNodeId());
            swarmManager.addSwarmMember(peer);
        }
        
        if (peer.isSwarmKey() && peer.getSwarmId() != this.swarmId) {
            keyNodePeers.add(peer.getNodeId());
            swarmManager.addKeyNode(peer);
        }
    }
    
    public void removePeer(UUID peerId) {
        PeerInfo peer = knownPeers.get(peerId);
        if (peer != null && peer.getSwarmId() == this.swarmId) {
            swarmManager.removeSwarmMember(peerId);
        }
        
        knownPeers.remove(peerId);
        swarmPeers.remove(peerId);
        keyNodePeers.remove(peerId);
    }
    
    public PeerInfo getPeer(UUID peerId) {
        return knownPeers.get(peerId);
    }
    
    public Collection<PeerInfo> getAllPeers() {
        return knownPeers.values();
    }
    
    public Collection<PeerInfo> getSwarmPeers() {
        return swarmPeers.stream()
                .map(knownPeers::get)
                .filter(Objects::nonNull)
                .toList();
    }
    
    public Collection<PeerInfo> getKeyNodePeers() {
        return keyNodePeers.stream()
                .map(knownPeers::get)
                .filter(Objects::nonNull)
                .toList();
    }
    
    // Message management methods (stubs for now)
    public void addMessage(ChatMessage message) {
        messageHistory.put(message.getMessageId(), message);
        updateClock(message.getLamportTimestamp());
    }
    
    public Collection<ChatMessage> getAllMessages() {
        return messageHistory.values().stream()
                .sorted(Comparator.comparingLong(ChatMessage::getLamportTimestamp)
                        .thenComparing(ChatMessage::getMessageId))
                .toList();
    }
    
    public boolean hasMessage(UUID messageId) {
        return messageHistory.containsKey(messageId);
    }
    
    @Override
    public String toString() {
        String keyStatus = isSwarmKey ? " [KEY NODE]" : "";
        return "Node: " + nickname + " (" + nodeId.toString().substring(0, 8) + "...) "
               + "@ " + ipAddress + ":" + port + " [Swarm " + swarmId + "]" + keyStatus;
    }

    private void refreshJoinMetadata() {
        this.joinTime = System.currentTimeMillis();
        if (swarmManager != null) {
            swarmManager.resetJoinTime(this.joinTime);
        }
        syncSelfPeerInfo();
    }
    
    private void syncSelfPeerInfo() {
        PeerInfo self = knownPeers.get(nodeId);
        if (self == null) {
            knownPeers.put(nodeId, createOwnPeerInfo());
            return;
        }
        self.setSwarmKey(isSwarmKey);
        self.setSwarmId(swarmId);
        self.setJoinTime(joinTime);
    }
    
    // ===== NETWORK OPERATIONS =====
    
    /**
     * Start the node and begin listening for packets
     */
    public void start() {
        if (running) {
            return;
        }
        refreshJoinMetadata();
        
        // Create new UDP handler if needed (for reconnection)
        if (udpHandler == null || !udpHandler.isRunning()) {
            try {
                udpHandler = new UDPHandler(port, this::handleIncomingPacket);
            } catch (Exception e) {
                System.err.println("Failed to create UDP handler: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        
        running = true;
        udpHandler.start();
        
        // Start discovery
        if (discoveryHandler != null) {
            discoveryHandler.start();
        }
        
        System.out.println("Node started: " + this);
        
        // After joining, request key nodes if not first member
        if (!getAllPeers().isEmpty()) {
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Wait for join to complete
                    swarmManager.requestKeyNodes();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
    
    /**
     * Stop the node and cleanup
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        // Send leave notification to all peers
        sendLeaveNotification();
        
        running = false;
        udpHandler.stop();
        
        // Stop discovery
        if (discoveryHandler != null) {
            discoveryHandler.stop();
        }
        
        System.out.println("Node stopped: " + nickname);
    }
    
    /**
     * Get list of discovered rooms
     */
    public Collection<RoomInfo> getDiscoveredRooms() {
        if (discoveryHandler != null) {
            return discoveryHandler.getDiscoveredRooms();
        }
        return Collections.emptyList();
    }
    
    /**
     * Join a network by connecting to a known peer
     */
    public void joinNetwork(String peerAddress, int peerPort) {
        System.out.println("Joining network via " + peerAddress + ":" + peerPort);
        
        // Send join request
        NetworkPacket joinPacket = new NetworkPacket(
            MessageType.JOIN_REQUEST,
            nodeId,
            nickname,
            incrementClock(),
            createOwnPeerInfo()
        );
        
        try {
            udpHandler.sendWithFragmentation(joinPacket, peerAddress, peerPort);
            System.out.println("Join request sent to " + peerAddress + ":" + peerPort);
        } catch (Exception e) {
            System.err.println("Failed to send join request: " + e.getMessage());
        }
    }
    
    /**
     * Send a chat message to the network
     */
    public void sendChatMessage(String content) {
        long timestamp = incrementClock();
        ChatMessage message = new ChatMessage(nodeId, nickname, content, timestamp);
        
        // Store locally first
        addMessage(message);
        
        // Create network packet
        NetworkPacket packet = new NetworkPacket(
            MessageType.CHAT_MESSAGE,
            nodeId,
            nickname,
            timestamp,
            message
        );
        
        // Broadcast to swarm peers
        broadcastToSwarm(packet);
        
        // If we're a key node, also send to other key nodes
        if (isSwarmKey) {
            broadcastToKeyNodes(packet);
        }
    }
    
    /**
     * Handle incoming network packets
     */
    private void handleIncomingPacket(NetworkPacket packet, String senderAddress, int senderPort) {
        try {
            // Handle fragmented packets
            NetworkPacket completePacket = fragmentAssembler.addFragment(packet);
            if (completePacket == null) {
                return; // Still waiting for more fragments
            }
            
            // Update Lamport clock
            updateClock(completePacket.getLamportTimestamp());
            
            // Handle based on type
            switch (completePacket.getType()) {
                case CHAT_MESSAGE -> handleChatMessage(completePacket);
                case JOIN_REQUEST -> handleJoinRequest(completePacket, senderAddress, senderPort);
                case PEER_DISCOVERY -> handlePeerDiscovery(completePacket);
                case PEER_LIST -> handlePeerList(completePacket);
                case HEARTBEAT -> handleHeartbeat(completePacket);
                case LEAVE_NOTIFY -> handleLeaveNotify(completePacket);
                case KEY_NODE_ANNOUNCE -> handleKeyNodeAnnounce(completePacket);
                case KEY_NODE_LIST -> handleKeyNodeList(completePacket);
                case KEY_NODE_REQUEST -> handleKeyNodeRequest(completePacket);
                default -> System.out.println("Unknown packet type: " + completePacket.getType());
            }
            
        } catch (Exception e) {
            System.err.println("Error handling packet: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ===== PACKET HANDLERS =====
    
    private void handleChatMessage(NetworkPacket packet) {
        ChatMessage message = (ChatMessage) packet.getPayload();
        
        // Check if we already have this message (deduplication)
        if (hasMessage(message.getMessageId())) {
            return;
        }
        
        // Store message
        addMessage(message);
        
        // Display message
        System.out.println(message);
        
        // Forward to swarm peers (except sender)
        forwardToSwarm(packet, packet.getSenderId());
        
        // If we're a key node, forward to other key nodes
        if (isSwarmKey) {
            forwardToKeyNodes(packet, packet.getSenderId());
        }
    }
    
    private void handleJoinRequest(NetworkPacket packet, String senderAddress, int senderPort) {
        PeerInfo newPeer = (PeerInfo) packet.getPayload();
        System.out.println("Join request from: " + newPeer.getNickname());
        
        // Add the new peer
        addPeer(newPeer);
        
        // Send our peer info back
        NetworkPacket discoveryPacket = new NetworkPacket(
            MessageType.PEER_DISCOVERY,
            nodeId,
            nickname,
            incrementClock(),
            createOwnPeerInfo()
        );
        
        try {
            udpHandler.sendWithFragmentation(discoveryPacket, senderAddress, senderPort);
        } catch (Exception e) {
            System.err.println("Failed to respond to join request: " + e.getMessage());
        }
        
        // Send them our peer list
        sendPeerListTo(senderAddress, senderPort);
        
        // Send them all chat history
        sendChatHistoryTo(senderAddress, senderPort);
        
        // Notify other peers about the new peer
        broadcastPeerDiscovery(newPeer);
        
        // If we're key node, share key node list with new member
        if (isSwarmKey) {
            swarmManager.shareKeyNodes();
        }
    }
    
    private void handlePeerDiscovery(NetworkPacket packet) {
        PeerInfo peer = (PeerInfo) packet.getPayload();
        System.out.println("Discovered peer: " + peer.getNickname());
        addPeer(peer);
    }
    
    private void handlePeerList(NetworkPacket packet) {
        @SuppressWarnings("unchecked")
        java.util.List<PeerInfo> peers = (java.util.List<PeerInfo>) packet.getPayload();
        System.out.println("Received peer list with " + peers.size() + " peers");
        
        for (PeerInfo peer : peers) {
            if (!peer.getNodeId().equals(nodeId)) {
                addPeer(peer);
            }
        }
    }
    
    private void handleHeartbeat(NetworkPacket packet) {
        UUID peerId = packet.getSenderId();
        PeerInfo peer = getPeer(peerId);
        if (peer != null) {
            peer.updateLastSeen();
        }
    }
    
    private void handleLeaveNotify(NetworkPacket packet) {
        UUID leavingPeerId = packet.getSenderId();
        PeerInfo peer = getPeer(leavingPeerId);
        if (peer != null) {
            System.out.println("Peer leaving: " + peer.getNickname());
            removePeer(leavingPeerId);
        }
    }
    
    // ===== HELPER METHODS =====
    
    private PeerInfo createOwnPeerInfo() {
        PeerInfo own = new PeerInfo(nodeId, nickname, ipAddress, port, swarmId);
        own.setSwarmKey(isSwarmKey);
        own.setJoinTime(joinTime);
        return own;
    }
    
    public void broadcastToSwarm(NetworkPacket packet) {
        udpHandler.broadcast(packet, getSwarmPeers());
    }
    
    public void updateSwarmKeyFlags(UUID keyNodeId) {
        for (PeerInfo peer : knownPeers.values()) {
            if (peer.getSwarmId() == this.swarmId) {
                peer.setSwarmKey(peer.getNodeId().equals(keyNodeId));
            }
        }
        syncSelfPeerInfo();
    }

    public void ensureSeedKey() {
        swarmManager.ensureKeyNode();
    }

    public void prepareForJoin() {
        setSwarmKey(false);
        setDiscoveryBroadcastEnabled(false);
    }

    public void setDiscoveryBroadcastEnabled(boolean enabled) {
        if (discoveryHandler != null) {
            discoveryHandler.setBroadcastEnabled(enabled);
        }
    }

    public void triggerDiscoveryBroadcast() {
        if (discoveryHandler != null) {
            discoveryHandler.triggerImmediateBroadcast();
        }
    }
    
    public void sendTo(NetworkPacket packet, String address, int port) throws Exception {
        udpHandler.sendWithFragmentation(packet, address, port);
    }
    
    private void broadcastToKeyNodes(NetworkPacket packet) {
        udpHandler.broadcast(packet, getKeyNodePeers());
    }
    
    private void forwardToSwarm(NetworkPacket packet, UUID excludeNodeId) {
        for (PeerInfo peer : getSwarmPeers()) {
            if (!peer.getNodeId().equals(excludeNodeId)) {
                try {
                    udpHandler.sendWithFragmentation(packet, peer.getIpAddress(), peer.getPort());
                } catch (Exception e) {
                    System.err.println("Failed to forward to " + peer.getNickname());
                }
            }
        }
    }
    
    private void forwardToKeyNodes(NetworkPacket packet, UUID excludeNodeId) {
        for (PeerInfo peer : getKeyNodePeers()) {
            if (!peer.getNodeId().equals(excludeNodeId)) {
                try {
                    udpHandler.sendWithFragmentation(packet, peer.getIpAddress(), peer.getPort());
                } catch (Exception e) {
                    System.err.println("Failed to forward to key node " + peer.getNickname());
                }
            }
        }
    }
    
    private void sendPeerListTo(String address, int port) {
        var peerList = new java.util.ArrayList<>(knownPeers.values());
        
        NetworkPacket packet = new NetworkPacket(
            MessageType.PEER_LIST,
            nodeId,
            nickname,
            incrementClock(),
            peerList
        );
        
        try {
            udpHandler.sendWithFragmentation(packet, address, port);
        } catch (Exception e) {
            System.err.println("Failed to send peer list: " + e.getMessage());
        }
    }
    
    private void sendChatHistoryTo(String address, int port) {
        for (ChatMessage message : getAllMessages()) {
            NetworkPacket packet = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                message.getSenderId(),
                message.getSenderNickname(),
                message.getLamportTimestamp(),
                message
            );
            
            try {
                udpHandler.sendWithFragmentation(packet, address, port);
                Thread.sleep(10); // Small delay between messages
            } catch (Exception e) {
                System.err.println("Failed to send chat history: " + e.getMessage());
            }
        }
    }
    
    private void broadcastPeerDiscovery(PeerInfo newPeer) {
        NetworkPacket packet = new NetworkPacket(
            MessageType.PEER_DISCOVERY,
            nodeId,
            nickname,
            incrementClock(),
            newPeer
        );
        
        broadcastToSwarm(packet);
    }
    
    private void sendLeaveNotification() {
        NetworkPacket packet = new NetworkPacket(
            MessageType.LEAVE_NOTIFY,
            nodeId,
            nickname,
            incrementClock(),
            null
        );
        
        broadcastToSwarm(packet);
        if (isSwarmKey) {
            broadcastToKeyNodes(packet);
        }
    }
    
    public boolean isRunning() {
        return running;
    }
    
    // ===== KEY NODE HANDLERS =====
    
    private void handleKeyNodeAnnounce(NetworkPacket packet) {
        PeerInfo keyNode = (PeerInfo) packet.getPayload();
        System.out.println("Key node announced: " + keyNode.getNickname());
        
        if (keyNode.getNodeId().equals(nodeId)) {
            if (!isSwarmKey) {
                setSwarmKey(true);
            }
            updateSwarmKeyFlags(nodeId);
            return;
        }

        addPeer(keyNode);
        
        if (keyNode.getSwarmId() == this.swarmId) {
            updateSwarmKeyFlags(keyNode.getNodeId());
        }
    }
    
    private void handleKeyNodeList(NetworkPacket packet) {
        @SuppressWarnings("unchecked")
        java.util.List<PeerInfo> keyNodes = (java.util.List<PeerInfo>) packet.getPayload();
        System.out.println("Received " + keyNodes.size() + " key nodes from other swarms");
        
        for (PeerInfo keyNode : keyNodes) {
            swarmManager.addKeyNode(keyNode);
        }
    }
    
    private void handleKeyNodeRequest(NetworkPacket packet) {
        // Only key nodes respond to this
        if (!isSwarmKey) {
            return;
        }
        
        System.out.println(packet.getSenderNickname() + " requested key node list");
        swarmManager.shareKeyNodes();
    }
}
