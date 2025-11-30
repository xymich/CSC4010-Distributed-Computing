import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private Set<UUID> receivedFileIds;
    
    // Message storage - using thread-safe collection
    private Map<UUID, ChatMessage> messageHistory;
    private final Queue<NetworkPacket> deferredPackets;
    private volatile boolean historySyncInProgress;
    private volatile boolean outboundMutedForSync;
    private volatile long highestHistoryTimestamp;
    private java.util.Timer historySyncTimer;
    private java.util.Timer peerTimeoutTimer;
    private static final long HISTORY_SYNC_TIMEOUT_MS = 5000; // 5 second timeout
    private static final long PEER_TIMEOUT_MS = 30000; // 30 seconds without heartbeat = dead
    private static final long HEARTBEAT_INTERVAL_MS = 10000; // Send heartbeat every 10 seconds
    
    // Pending message tracking for delivery status
    private Set<UUID> pendingMessages;  // Messages awaiting ACK
    private List<MessageStatusListener> messageStatusListeners;
    private List<RoomDiscoveryListener> roomDiscoveryListeners;
    
    // Network components
    private UDPHandler udpHandler;
    private FragmentAssembler fragmentAssembler;
    private DiscoveryHandler discoveryHandler;
    private SwarmManager swarmManager;
    private boolean running;
    
    // Join time tracking
    private long joinTime;
    private Path logFilePath;
    private final DateTimeFormatter logTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
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
        
        this.swarmId = deriveSwarmId(this.roomId);
        this.isSwarmKey = false;
        
        this.lamportClock = new AtomicLong(0);
        
        this.knownPeers = new ConcurrentHashMap<>();
        this.swarmPeers = ConcurrentHashMap.newKeySet();
        this.keyNodePeers = ConcurrentHashMap.newKeySet();
        this.receivedFileIds = ConcurrentHashMap.newKeySet();
        this.messageHistory = new ConcurrentHashMap<>();
        this.deferredPackets = new ConcurrentLinkedQueue<>();
        this.historySyncInProgress = false;
        this.outboundMutedForSync = false;
        this.highestHistoryTimestamp = 0L;
        this.pendingMessages = ConcurrentHashMap.newKeySet();
        this.messageStatusListeners = new ArrayList<>();
        this.roomDiscoveryListeners = new ArrayList<>();
        
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
            new DiscoveryHandler.DiscoveryListener() {
                @Override
                public int getCurrentMemberCount() {
                    return getSwarmMemberCount();
                }
                
                @Override
                public void onRoomDiscovered(RoomInfo room) {
                    notifyRoomDiscovered(room);
                }
            }
        );
        discoveryHandler.setBroadcastEnabled(false); // enable once we become an active room
        knownPeers.put(nodeId, createOwnPeerInfo());
        this.logFilePath = initLogPath();
    }
    
    private void notifyRoomDiscovered(RoomInfo room) {
        for (RoomDiscoveryListener listener : roomDiscoveryListeners) {
            listener.onRoomDiscovered(room);
        }
    }
    
    public void addRoomDiscoveryListener(RoomDiscoveryListener listener) {
        roomDiscoveryListeners.add(listener);
    }
    
    public void removeRoomDiscoveryListener(RoomDiscoveryListener listener) {
        roomDiscoveryListeners.remove(listener);
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
        
        // If we're a key node and peer is from different swarm, they might be trying to join us
        // Update their swarm ID to match ours so they become part of our swarm
        boolean peerJoiningOurSwarm = isSwarmKey && peer.getSwarmId() != this.swarmId && !peer.isSwarmKey();
        if (peerJoiningOurSwarm) {
            peer.setSwarmId(this.swarmId);
            System.out.println("Peer " + peer.getNickname() + " joining our swarm (updated their swarm ID)");
        }
        
        adoptSwarmFromPeer(peer);

        boolean sameSwarm = peer.getSwarmId() == this.swarmId;
        boolean isForeignKey = peer.isSwarmKey() && !sameSwarm;
        if (!sameSwarm && !isForeignKey) {
            return; // Do not keep arbitrary members from other swarms
        }

        knownPeers.put(peer.getNodeId(), peer);
        if (sameSwarm) {
            swarmPeers.add(peer.getNodeId());
            swarmManager.addSwarmMember(peer);
        } else if (isForeignKey) {
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

    public int getSwarmMemberCount() {
        return swarmPeers.size() + 1; // include self
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
        appendMessageToLog(message);
    }
    
    public Collection<ChatMessage> getAllMessages() {
        return messageHistory.values().stream()
                .sorted(Comparator.comparingLong(ChatMessage::getLamportTimestamp)
                        .thenComparing(ChatMessage::getMessageId))
                .toList();
    }

    public boolean sendFile(String fileName, byte[] data) {
        if (!running) {
            System.out.println("Cannot send file while node is stopped.");
            return false;
        }
        if (data == null || data.length == 0) {
            System.out.println("File is empty. Nothing to send.");
            return false;
        }
        FileTransfer transfer = new FileTransfer(
            UUID.randomUUID(),
            fileName,
            data.length,
            data,
            nickname
        );

        NetworkPacket packet = new NetworkPacket(
            MessageType.FILE_TRANSFER,
            nodeId,
            nickname,
            incrementClock(),
            transfer
        );

        broadcastToSwarm(packet);
        broadcastToKeyNodes(packet);
        System.out.println("Broadcasting file '" + fileName + "' to swarm peers...");
        return true;
    }

    public boolean rebuildChatHistory() {
        boolean hasSwarmPeers = !swarmPeers.isEmpty();
        boolean hasKeyPeers = !keyNodePeers.isEmpty();
        if (!hasSwarmPeers && !hasKeyPeers) {
            System.out.println("No peers available to rebuild chat history.");
            return false;
        }

        if (historySyncInProgress) {
            System.out.println("History sync already in progress. Please wait.");
            return false;
        }

        beginHistorySync("manual resync request");
        messageHistory.clear();
        System.out.println("Requesting chat history sync from peers...");

        NetworkPacket syncRequest = new NetworkPacket(
            MessageType.MESSAGE_SYNC,
            nodeId,
            nickname,
            incrementClock(),
            null
        );

        broadcastToSwarm(syncRequest);
        broadcastToKeyNodes(syncRequest);
        requestKeyStatusFromPeers();
        return true;
    }

    private void requestKeyStatusFromPeers() {
        NetworkPacket request = new NetworkPacket(
            MessageType.KEY_NODE_REQUEST,
            nodeId,
            nickname,
            incrementClock(),
            null
        );
        broadcastToSwarm(request);
        broadcastToKeyNodes(request);
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

    private void adoptSwarmFromPeer(PeerInfo peer) {
        if (isSwarmKey) {
            return;
        }
        if (!peer.isSwarmKey()) {
            return;
        }
        if (!swarmPeers.isEmpty()) {
            return;
        }
        if (this.swarmId == peer.getSwarmId()) {
            return;
        }
        System.out.println("Adopting swarm ID " + peer.getSwarmId() + " from key node " + peer.getNickname());
        setSwarmId(peer.getSwarmId());
    }

    private Path initLogPath() {
        try {
            Path logDir = Paths.get("logs");
            Files.createDirectories(logDir);
            String safeNickname = nickname.replaceAll("[^A-Za-z0-9_-]", "_");
            String filename = String.format("%s_%s.log", safeNickname, nodeId.toString().substring(0, 8));
            Path path = logDir.resolve(filename);
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            return path;
        } catch (Exception e) {
            System.err.println("Failed to prepare log file: " + e.getMessage());
            return null;
        }
    }

    private void appendMessageToLog(ChatMessage message) {
        if (logFilePath == null) {
            return;
        }
        String line = String.format(
            "%s [%s] %s: %s%n",
            java.time.LocalDateTime.now().format(logTimestampFormatter),
            swarmId,
            message.getSenderNickname(),
            message.getContent()
        );
        try {
            Files.writeString(logFilePath, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Failed to write chat log: " + e.getMessage());
        }
    }

    private int deriveSwarmId(UUID sourceId) {
        int hash = sourceId.hashCode();
        return Math.floorMod(hash, 100_000) + 1; // keep ids positive and within a readable range
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
        
        // Start heartbeat/timeout timer
        startHeartbeatTimer();
        
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
    
    private void startHeartbeatTimer() {
        stopHeartbeatTimer();
        peerTimeoutTimer = new java.util.Timer("PeerHeartbeat", true);
        
        // Send heartbeat periodically
        peerTimeoutTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                if (running) {
                    sendHeartbeat();
                    checkPeerTimeouts();
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
    }
    
    private void stopHeartbeatTimer() {
        if (peerTimeoutTimer != null) {
            peerTimeoutTimer.cancel();
            peerTimeoutTimer = null;
        }
    }
    
    private void sendHeartbeat() {
        NetworkPacket heartbeat = new NetworkPacket(
            MessageType.HEARTBEAT,
            nodeId,
            nickname,
            incrementClock(),
            null
        );
        broadcastToSwarm(heartbeat);
        if (isSwarmKey) {
            broadcastToKeyNodes(heartbeat);
        }
    }
    
    private void checkPeerTimeouts() {
        long now = System.currentTimeMillis();
        List<UUID> timedOut = new ArrayList<>();
        
        for (PeerInfo peer : knownPeers.values()) {
            if (peer.getNodeId().equals(nodeId)) {
                continue; // Skip self
            }
            if (now - peer.getLastSeen() > PEER_TIMEOUT_MS) {
                timedOut.add(peer.getNodeId());
            }
        }
        
        for (UUID peerId : timedOut) {
            PeerInfo peer = knownPeers.get(peerId);
            if (peer != null) {
                System.out.println("Peer timed out (no heartbeat): " + peer.getNickname());
                removePeer(peerId);
            }
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
        
        // Stop heartbeat timer
        stopHeartbeatTimer();
        
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
        beginHistorySync("joining network");
        
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
     * @return The message ID for tracking delivery status, or null if sending was blocked
     */
    public UUID sendChatMessage(String content) {
        if (outboundMutedForSync) {
            System.out.println("History sync in progress. Please wait until it completes before sending messages.");
            return null;
        }
        long timestamp = incrementClock();
        ChatMessage message = new ChatMessage(nodeId, nickname, content, timestamp);
        UUID messageId = message.getMessageId();
        
        // Track as pending if we have peers to send to
        int peerCount = swarmPeers.size() + (isSwarmKey ? keyNodePeers.size() : 0);
        if (peerCount > 0) {
            pendingMessages.add(messageId);
        } else {
            // No peers - immediately mark as delivered (or "sent to self")
            notifyMessageDelivered(messageId);
        }
        
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
        
        return messageId;
    }
    
    /**
     * Add a listener for message delivery status updates
     */
    public void addMessageStatusListener(MessageStatusListener listener) {
        messageStatusListeners.add(listener);
    }
    
    /**
     * Remove a message delivery status listener
     */
    public void removeMessageStatusListener(MessageStatusListener listener) {
        messageStatusListeners.remove(listener);
    }
    
    /**
     * Check if a message is still pending delivery confirmation
     */
    public boolean isMessagePending(UUID messageId) {
        return pendingMessages.contains(messageId);
    }
    
    /**
     * Notify listeners that a message has been delivered
     */
    private void notifyMessageDelivered(UUID messageId) {
        for (MessageStatusListener listener : messageStatusListeners) {
            listener.onMessageStatusChanged(messageId, true);
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
            
            if (shouldDeferDuringSync(completePacket)) {
                deferPacket(completePacket);
                return;
            }

            // Update Lamport clock
            updateClock(completePacket.getLamportTimestamp());
            
            // Handle based on type
            switch (completePacket.getType()) {
                case CHAT_MESSAGE -> handleChatMessage(completePacket, senderAddress, senderPort);
                case MESSAGE_ACK -> handleMessageAck(completePacket);
                case HISTORY_SNAPSHOT -> handleHistorySnapshot(completePacket);
                case JOIN_REQUEST -> handleJoinRequest(completePacket, senderAddress, senderPort);
                case PEER_DISCOVERY -> handlePeerDiscovery(completePacket);
                case PEER_LIST -> handlePeerList(completePacket);
                case HEARTBEAT -> handleHeartbeat(completePacket);
                case LEAVE_NOTIFY -> handleLeaveNotify(completePacket);
                case MESSAGE_SYNC -> handleMessageSync(completePacket, senderAddress, senderPort);
                case KEY_NODE_ANNOUNCE -> handleKeyNodeAnnounce(completePacket);
                case KEY_NODE_LIST -> handleKeyNodeList(completePacket);
                case KEY_NODE_REQUEST -> handleKeyNodeRequest(completePacket);
                case FILE_TRANSFER -> handleFileTransfer(completePacket);
                default -> System.out.println("Unknown packet type: " + completePacket.getType());
            }
            
        } catch (Exception e) {
            System.err.println("Error handling packet: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ===== PACKET HANDLERS =====
    
    private void handleChatMessage(NetworkPacket packet, String senderAddress, int senderPort) {
        processIncomingChat(packet, true);
        
        // Send ACK back to the original sender
        ChatMessage message = (ChatMessage) packet.getPayload();
        if (message != null) {
            sendMessageAck(message.getMessageId(), senderAddress, senderPort);
        }
    }
    
    private void handleMessageAck(NetworkPacket packet) {
        UUID messageId = (UUID) packet.getPayload();
        if (messageId != null && pendingMessages.remove(messageId)) {
            // Message was confirmed delivered by at least one peer
            notifyMessageDelivered(messageId);
        }
    }
    
    private void sendMessageAck(UUID messageId, String targetAddress, int targetPort) {
        NetworkPacket ackPacket = new NetworkPacket(
            MessageType.MESSAGE_ACK,
            nodeId,
            nickname,
            getClock(),
            messageId
        );
        try {
            udpHandler.sendWithFragmentation(ackPacket, targetAddress, targetPort);
        } catch (IOException e) {
            // ACK delivery is best-effort
        }
    }

    private void processIncomingChat(NetworkPacket packet, boolean shouldForward) {
        ChatMessage message = (ChatMessage) packet.getPayload();

        if (message == null) {
            System.out.println("[DEBUG] processIncomingChat: message is null");
            return;
        }

        // Check if we already have this message (deduplication)
        if (hasMessage(message.getMessageId())) {
            return;
        }

        // Store message
        addMessage(message);

        // Display message - only show messages from OTHER nodes (we display our own separately)
        if (!message.getSenderId().equals(nodeId)) {
            System.out.println(message.toString());
        }

        if (!shouldForward) {
            return;
        }

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
        // Share key status along with history to ensure metadata is consistent
        swarmManager.broadcastKeyNodeStatus();
        
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

    private void handleMessageSync(NetworkPacket packet, String senderAddress, int senderPort) {
        if (packet.getSenderId().equals(nodeId)) {
            return; // ignore self
        }

        PeerInfo requester = getPeer(packet.getSenderId());
        if (requester != null && requester.getSwarmId() != this.swarmId) {
            System.out.println("Ignoring history request from different swarm node " + requester.getNickname());
            return;
        }

        System.out.println("History sync requested by " + packet.getSenderNickname() +
                           ". Sending " + messageHistory.size() + " messages.");
        sendChatHistoryTo(senderAddress, senderPort);
        
        // Only broadcast key status if WE are actually the key node
        if (isSwarmKey) {
            swarmManager.broadcastKeyNodeStatus();
        }
    }

    private void handleFileTransfer(NetworkPacket packet) {
        FileTransfer transfer = (FileTransfer) packet.getPayload();
        if (transfer == null || transfer.getData() == null) {
            System.out.println("Received invalid file transfer payload.");
            return;
        }

        if (!receivedFileIds.add(transfer.getFileId())) {
            return; // already processed
        }

        try {
            Path downloadDir = Paths.get("downloads", nickname.replaceAll("[^A-Za-z0-9_-]", "_"));
            Files.createDirectories(downloadDir);
            String sanitizedName = sanitizeFileName(transfer.getFileName());
            String finalName = String.format("%s_%s_%d",
                    transfer.getSenderNickname().replaceAll("[^A-Za-z0-9_-]", "_"),
                    sanitizedName,
                    System.currentTimeMillis());
            Path filePath = downloadDir.resolve(finalName);
            Files.write(filePath, transfer.getData());
            System.out.println("Received file '" + transfer.getFileName() + "' (" +
                    formatBytes(transfer.getFileSize()) + ") from " + transfer.getSenderNickname());
            System.out.println("Saved to " + filePath.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to save incoming file: " + e.getMessage());
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format("%.2f MB", mb);
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
        leaveCurrentSwarm(!swarmPeers.isEmpty());
        setSwarmKey(false);
        setDiscoveryBroadcastEnabled(false);
        // Reset join time so we don't steal key status from existing swarm members
        refreshJoinMetadata();
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

    public void addDiscoverySeed(String address, int port) {
        if (discoveryHandler != null) {
            discoveryHandler.addUnicastTarget(address, port);
            discoveryHandler.triggerImmediateBroadcast();
        }
    }

    public Collection<InetSocketAddress> getDiscoverySeeds() {
        if (discoveryHandler != null) {
            return discoveryHandler.getUnicastTargets();
        }
        return Collections.emptyList();
    }
    
    public void sendTo(NetworkPacket packet, String address, int port) throws IOException {
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
                } catch (IOException e) {
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
                } catch (IOException e) {
                    System.err.println("Failed to forward to key node " + peer.getNickname());
                }
            }
        }
    }

    private void leaveCurrentSwarm(boolean notifyPeers) {
        if (notifyPeers && running) {
            sendLeaveNotification();
        }
        knownPeers.clear();
        swarmPeers.clear();
        keyNodePeers.clear();
        swarmManager.resetMembership();
        historySyncInProgress = false;
        outboundMutedForSync = false;
        deferredPackets.clear();
        // Clear message history and file IDs when leaving room
        messageHistory.clear();
        receivedFileIds.clear();
        knownPeers.put(nodeId, createOwnPeerInfo());
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
        } catch (IOException e) {
            System.err.println("Failed to send peer list: " + e.getMessage());
        }
    }
    
    private void sendChatHistoryTo(String address, int port) {
        java.util.List<ChatMessage> history = new java.util.ArrayList<>(getAllMessages());
        long cursor = history.stream()
            .mapToLong(ChatMessage::getLamportTimestamp)
            .max()
            .orElse(getClock());

        ChatHistorySnapshot snapshot = new ChatHistorySnapshot(history, cursor);
        NetworkPacket packet = new NetworkPacket(
            MessageType.HISTORY_SNAPSHOT,
            nodeId,
            nickname,
            incrementClock(),
            snapshot
        );

        try {
            udpHandler.sendWithFragmentation(packet, address, port);
        } catch (IOException e) {
            System.err.println("Failed to send chat history snapshot: " + e.getMessage());
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

    private void beginHistorySync(String reason) {
        if (historySyncInProgress) {
            return;
        }
        historySyncInProgress = true;
        outboundMutedForSync = true;
        highestHistoryTimestamp = 0L;
        deferredPackets.clear();
        System.out.println("Starting history sync (" + reason + "). Outbound chat muted until completion.");
        
        // Start timeout timer - if no snapshot received in time, complete anyway
        cancelHistorySyncTimer();
        historySyncTimer = new java.util.Timer("HistorySyncTimeout", true);
        historySyncTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                if (historySyncInProgress) {
                    System.out.println("History sync timed out. Completing with available data.");
                    completeHistorySync();
                }
            }
        }, HISTORY_SYNC_TIMEOUT_MS);
    }
    
    private void cancelHistorySyncTimer() {
        if (historySyncTimer != null) {
            historySyncTimer.cancel();
            historySyncTimer = null;
        }
    }

    private boolean shouldDeferDuringSync(NetworkPacket packet) {
        return historySyncInProgress && packet.getType() == MessageType.CHAT_MESSAGE;
    }

    private void deferPacket(NetworkPacket packet) {
        deferredPackets.add(packet);
    }

    private void handleHistorySnapshot(NetworkPacket packet) {
        ChatHistorySnapshot snapshot = (ChatHistorySnapshot) packet.getPayload();
        if (snapshot == null) {
            System.out.println("Received empty history snapshot from " + packet.getSenderNickname());
            completeHistorySyncIfPending();
            return;
        }

        java.util.List<ChatMessage> snapshotMessages = new java.util.ArrayList<>(snapshot.getMessages());
        snapshotMessages.sort(Comparator
            .comparingLong(ChatMessage::getLamportTimestamp)
            .thenComparing(ChatMessage::getMessageId));

        int applied = 0;
        for (ChatMessage message : snapshotMessages) {
            if (hasMessage(message.getMessageId())) {
                continue;
            }
            addMessage(message);
            System.out.println(message.toString());
            applied++;
        }

        highestHistoryTimestamp = Math.max(highestHistoryTimestamp, snapshot.getLamportCursor());
        System.out.println("Applied history snapshot (" + applied + " new messages) from " + packet.getSenderNickname());
        completeHistorySyncIfPending();
    }

    private void completeHistorySyncIfPending() {
        if (!historySyncInProgress) {
            return;
        }
        completeHistorySync();
    }

    private void completeHistorySync() {
        cancelHistorySyncTimer();  // Cancel timeout since we completed normally
        long newClock = Math.max(lamportClock.get(), highestHistoryTimestamp) + 1;
        lamportClock.set(newClock);
        historySyncInProgress = false;
        outboundMutedForSync = false;
        flushDeferredPackets();
        System.out.println("History sync complete. You may resume chatting.");
    }

    private void flushDeferredPackets() {
        if (deferredPackets.isEmpty()) {
            return;
        }

        java.util.List<NetworkPacket> drained = new java.util.ArrayList<>();
        NetworkPacket queued;
        while ((queued = deferredPackets.poll()) != null) {
            drained.add(queued);
        }

        drained.sort(Comparator
            .comparingLong(NetworkPacket::getLamportTimestamp)
            .thenComparing(packet -> packet.getSenderId() == null ? new UUID(0L, 0L) : packet.getSenderId()));

        for (NetworkPacket deferred : drained) {
            if (deferred.getType() == MessageType.CHAT_MESSAGE) {
                processIncomingChat(deferred, false);
            }
        }
    }
    
    // ===== KEY NODE HANDLERS =====
    
    private void handleKeyNodeAnnounce(NetworkPacket packet) {
        PeerInfo keyNode = (PeerInfo) packet.getPayload();
        System.out.println("Key node announced: " + keyNode.getNickname());
        
        if (keyNode.getNodeId().equals(nodeId)) {
            // This is ourselves announcing - ignore
            return;
        }

        // Add/update the peer info
        addPeer(keyNode);
        
        // Only consider key status changes within our swarm
        if (keyNode.getSwarmId() == this.swarmId) {
            // If we're already the key node, only defer to someone who joined BEFORE us
            if (isSwarmKey) {
                long ourJoinTime = swarmManager.getJoinTime();
                long theirJoinTime = keyNode.getJoinTime();
                
                // Only give up key if they joined before us (they have seniority)
                if (theirJoinTime < ourJoinTime) {
                    System.out.println(">>> Deferring key node status to " + keyNode.getNickname() + " (joined earlier)");
                    setSwarmKey(false);
                    updateSwarmKeyFlags(keyNode.getNodeId());
                } else {
                    // We have seniority - re-announce ourselves as key
                    System.out.println(">>> Ignoring key claim from " + keyNode.getNickname() + " (we joined earlier)");
                    swarmManager.broadcastKeyNodeStatus();
                }
            } else {
                // We're not the key - just update who the key is
                updateSwarmKeyFlags(keyNode.getNodeId());
            }
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
        swarmManager.broadcastKeyNodeStatus();
    }
}
