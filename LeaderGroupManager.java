import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LeaderGroupManager {
    
    private ChatNode node;
    private Map<UUID, PeerInfo> leaderMembers;
    private Map<UUID, PeerInfo> knownKeyNodes;
    private PeerInfo currentKeyNode;
    private long joinTime;
    
    public LeaderGroupManager(ChatNode node) {
        this.node = node;
        this.leaderMembers = new ConcurrentHashMap<>();
        this.knownKeyNodes = new ConcurrentHashMap<>();
        this.joinTime = System.currentTimeMillis();
    }

    public void resetJoinTime(long newJoinTime) {
        this.joinTime = newJoinTime;
    }

    public void ensureKeyNode() {
        if (!node.isLeaderKey()) {
            becomeKeyNode();
        }
    }
    
    /**
     * Add a peer to our leader
     */
    public void addLeaderMember(PeerInfo peer) {
        leaderMembers.put(peer.getNodeId(), peer);
        checkKeyNodeStatus();
    }
    
    /**
     * Remove a peer from our leader
     */
    public void removeLeaderMember(UUID peerId) {
        leaderMembers.remove(peerId);
        
        // Check if removed peer was key node
        if (currentKeyNode != null && currentKeyNode.getNodeId().equals(peerId)) {
            promoteNewKeyNode();
        }
    }
    
    /**
     * Add a key node from another leader
     */
    public void addKeyNode(PeerInfo keyNode) {
        if (!keyNode.isLeaderKey()) {
            return; // Only track actual key nodes
        }
        knownKeyNodes.put(keyNode.getNodeId(), keyNode);
        
        // If we're a key node, connect to this key node
        if (node.isLeaderKey()) {
            node.addPeer(keyNode);
        }
    }
    
    /**
     * Check if we should become key node
     */
    private void checkKeyNodeStatus() {
        // Find oldest member (lowest join time)
        PeerInfo oldest = null;
        long oldestTime = joinTime;
        
        for (PeerInfo peer : leaderMembers.values()) {
            if (peer.getJoinTime() < oldestTime) {
                oldest = peer;
                oldestTime = peer.getJoinTime();
            }
        }
        
        // Are we the oldest?
        boolean shouldBeKey = (oldest == null); // No older peers found
        
        if (shouldBeKey && !node.isLeaderKey()) {
            becomeKeyNode();
        } else if (!shouldBeKey && node.isLeaderKey() && oldest != null) {
            // Someone older exists, demote ourselves
            demoteFromKeyNode(oldest);
        }
        
        currentKeyNode = shouldBeKey ? createOwnPeerInfo() : oldest;
    }
    
    /**
     * Promote ourselves to key node
     */
    private void becomeKeyNode() {
        System.out.println(">>> Becoming leader key node");
        node.setLeaderKey(true);
        node.updateLeaderKeyFlags(node.getNodeId());
        
        // Connect to all known key nodes
        for (PeerInfo keyNode : knownKeyNodes.values()) {
            node.addPeer(keyNode);
        }
        
        // Announce to leader
        broadcastKeyNodeStatus();
    }
    
    /**
     * Demote from key node to regular member
     */
    private void demoteFromKeyNode(PeerInfo newKeyNode) {
        System.out.println(">>> " + newKeyNode.getNickname() + " is now leader key node");
        node.setLeaderKey(false);
        currentKeyNode = newKeyNode;
        node.updateLeaderKeyFlags(newKeyNode.getNodeId());
        
        // Disconnect from other key nodes (keep only leader connections)
        for (UUID keyNodeId : knownKeyNodes.keySet()) {
            // Don't remove if they're also in our leader
            if (!leaderMembers.containsKey(keyNodeId)) {
                node.removePeer(keyNodeId);
            }
        }
    }
    
    /**
     * Promote new key node when current one leaves
     */
    private void promoteNewKeyNode() {
        System.out.println(">>> Key node left, finding replacement...");
        
        // Find next oldest member
        PeerInfo nextOldest = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (PeerInfo peer : leaderMembers.values()) {
            if (peer.getJoinTime() < oldestTime) {
                nextOldest = peer;
                oldestTime = peer.getJoinTime();
            }
        }
        
        // Am I the next oldest?
        if (nextOldest == null || joinTime < oldestTime) {
            becomeKeyNode();
        } else {
            currentKeyNode = nextOldest;
            node.updateLeaderKeyFlags(nextOldest.getNodeId());
            System.out.println(">>> " + nextOldest.getNickname() + " is new key node");
        }
    }
    
    /**
     * Broadcast our key node status to leader
     */
    public void broadcastKeyNodeStatus() {
        NetworkPacket packet = new NetworkPacket(
            MessageType.KEY_NODE_ANNOUNCE,
            node.getNodeId(),
            node.getNickname(),
            node.incrementClock(),
            createOwnPeerInfo()
        );
        
        // Broadcast to own leader
        node.broadcastToLeader(packet);
        
        // Also send to all known key nodes from other leaders
        for (PeerInfo keyNode : knownKeyNodes.values()) {
            try {
                node.sendTo(packet, keyNode.getIpAddress(), keyNode.getPort());
            } catch (IOException e) {
                System.err.println("Failed to notify key node: " + keyNode.getNickname());
            }
        }
    }
    
    /**
     * Get current key node for our leader
     */
    public PeerInfo getCurrentKeyNode() {
        return currentKeyNode;
    }
    
    /**
     * Get all known key nodes from other leaders
     */
    public Collection<PeerInfo> getKnownKeyNodes() {
        return new ArrayList<>(knownKeyNodes.values());
    }
    
    /**
     * Share known key nodes with leader members
     */
    public void shareKeyNodes() {
        if (!node.isLeaderKey()) {
            return; // Only key nodes share this info
        }
        
        NetworkPacket packet = new NetworkPacket(
            MessageType.KEY_NODE_LIST,
            node.getNodeId(),
            node.getNickname(),
            node.incrementClock(),
            new ArrayList<>(knownKeyNodes.values())
        );
        
        node.broadcastToLeader(packet);
    }
    
    /**
     * Request key node list from current key node
     */
    public void requestKeyNodes() {
        if (currentKeyNode == null) {
            return;
        }
        
        NetworkPacket packet = new NetworkPacket(
            MessageType.KEY_NODE_REQUEST,
            node.getNodeId(),
            node.getNickname(),
            node.incrementClock(),
            null
        );
        
        try {
            node.sendTo(packet, currentKeyNode.getIpAddress(), currentKeyNode.getPort());
        } catch (IOException e) {
            System.err.println("Failed to request key nodes: " + e.getMessage());
        }
    }
    
    private PeerInfo createOwnPeerInfo() {
        PeerInfo own = new PeerInfo(
            node.getNodeId(), 
            node.getNickname(), 
            node.getIpAddress(), 
            node.getPort(), 
            node.getLeaderId()
        );
        own.setLeaderKey(node.isLeaderKey());
        own.setJoinTime(joinTime);
        return own;
    }
    
    public long getJoinTime() {
        return joinTime;
    }
    
    public Collection<PeerInfo> getLeaderMembers() {
        return new ArrayList<>(leaderMembers.values());
    }

    /**
     * Clear all state so the node can safely join a fresh leader.
     */
    public void resetMembership() {
        leaderMembers.clear();
        knownKeyNodes.clear();
        currentKeyNode = null;
        node.setLeaderKey(false);
    }
}
