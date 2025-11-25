import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ChatNode {
    
    // Node identity
    private UUID nodeId;
    private String nickname;
    private String ipAddress;
    private int port;
    
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
    private boolean running;
    
    public ChatNode(String nickname, String ipAddress, int port) {
        this.nodeId = UUID.randomUUID();
        this.nickname = nickname;
        this.ipAddress = ipAddress;
        this.port = port;
        
        this.swarmId = 0;  // Default swarm
        this.isSwarmKey = false;
        
        this.lamportClock = new AtomicLong(0);
        
        this.knownPeers = new ConcurrentHashMap<>();
        this.swarmPeers = ConcurrentHashMap.newKeySet();
        this.keyNodePeers = ConcurrentHashMap.newKeySet();
        this.messageHistory = new ConcurrentHashMap<>();
        
        this.fragmentAssembler = new FragmentAssembler();
        this.running = false;
        
        try {
            this.udpHandler = new UDPHandler(port, this::handleIncomingPacket);
        } catch (Exception e) {
            System.err.println("Failed to create UDP handler: " + e.getMessage());
            e.printStackTrace();
        }
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
    public int getSwarmId() { return swarmId; }
    public boolean isSwarmKey() { return isSwarmKey; }
    
    // Setters
    public void setSwarmId(int swarmId) { this.swarmId = swarmId; }
    public void setSwarmKey(boolean isSwarmKey) { this.isSwarmKey = isSwarmKey; }
    
    // Peer management methods (stubs for now)
    public void addPeer(PeerInfo peer) {
        knownPeers.put(peer.getNodeId(), peer);
        if (peer.getSwarmId() == this.swarmId) {
            swarmPeers.add(peer.getNodeId());
        }
        if (peer.isSwarmKey() && peer.getSwarmId() != this.swarmId) {
            keyNodePeers.add(peer.getNodeId());
        }
    }
    
    public void removePeer(UUID peerId) {
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
    
    // ===== NETWORK OPERATIONS =====
    
    /**
     * Start the node and begin listening for packets
     */
    public void start() {
        if (running) {
            return;
        }
        
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
        System.out.println("Node started: " + this);
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
        System.out.println("Node stopped: " + nickname);
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
        return own;
    }
    
    private void broadcastToSwarm(NetworkPacket packet) {
        udpHandler.broadcast(packet, getSwarmPeers());
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
}
