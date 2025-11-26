import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SwarmManager {
    
    private ChatNode node;
    private Map<UUID, PeerInfo> swarmMembers;
    private Map<UUID, PeerInfo> knownKeyNodes;
    private PeerInfo currentKeyNode;
    private long joinTime;
    
    public SwarmManager(ChatNode node) {
        this.node = node;
        this.swarmMembers = new ConcurrentHashMap<>();
        this.knownKeyNodes = new ConcurrentHashMap<>();
        this.joinTime = System.currentTimeMillis();
    }

    public void resetJoinTime(long newJoinTime) {
        this.joinTime = newJoinTime;
    }

    public void ensureKeyNode() {
        if (!node.isSwarmKey()) {
            becomeKeyNode();
        }
    }
    
    /**
     * Add a peer to our swarm
     */
    public void addSwarmMember(PeerInfo peer) {
        swarmMembers.put(peer.getNodeId(), peer);
        checkKeyNodeStatus();
    }
    
    /**
     * Remove a peer from our swarm
     */
    public void removeSwarmMember(UUID peerId) {
        swarmMembers.remove(peerId);
        
        // Check if removed peer was key node
        if (currentKeyNode != null && currentKeyNode.getNodeId().equals(peerId)) {
            promoteNewKeyNode();
        }
    }
    
    /**
     * Add a key node from another swarm
     */
    public void addKeyNode(PeerInfo keyNode) {
        if (!keyNode.isSwarmKey()) {
            return; // Only track actual key nodes
        }
        knownKeyNodes.put(keyNode.getNodeId(), keyNode);
        
        // If we're a key node, connect to this key node
        if (node.isSwarmKey()) {
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
        
        for (PeerInfo peer : swarmMembers.values()) {
            if (peer.getJoinTime() < oldestTime) {
                oldest = peer;
                oldestTime = peer.getJoinTime();
            }
        }
        
        // Are we the oldest?
        boolean shouldBeKey = (oldest == null); // No older peers found
        
        if (shouldBeKey && !node.isSwarmKey()) {
            becomeKeyNode();
        } else if (!shouldBeKey && node.isSwarmKey() && oldest != null) {
            // Someone older exists, demote ourselves
            demoteFromKeyNode(oldest);
        }
        
        currentKeyNode = shouldBeKey ? createOwnPeerInfo() : oldest;
    }
    
    /**
     * Promote ourselves to key node
     */
    private void becomeKeyNode() {
        System.out.println(">>> Becoming swarm key node");
        node.setSwarmKey(true);
        node.updateSwarmKeyFlags(node.getNodeId());
        
        // Connect to all known key nodes
        for (PeerInfo keyNode : knownKeyNodes.values()) {
            node.addPeer(keyNode);
        }
        
        // Announce to swarm
        broadcastKeyNodeStatus();
    }
    
    /**
     * Demote from key node to regular member
     */
    private void demoteFromKeyNode(PeerInfo newKeyNode) {
        System.out.println(">>> " + newKeyNode.getNickname() + " is now swarm key node");
        node.setSwarmKey(false);
        currentKeyNode = newKeyNode;
        node.updateSwarmKeyFlags(newKeyNode.getNodeId());
        
        // Disconnect from other key nodes (keep only swarm connections)
        for (UUID keyNodeId : knownKeyNodes.keySet()) {
            // Don't remove if they're also in our swarm
            if (!swarmMembers.containsKey(keyNodeId)) {
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
        
        for (PeerInfo peer : swarmMembers.values()) {
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
            node.updateSwarmKeyFlags(nextOldest.getNodeId());
            System.out.println(">>> " + nextOldest.getNickname() + " is new key node");
        }
    }
    
    /**
     * Broadcast our key node status to swarm
     */
    private void broadcastKeyNodeStatus() {
        NetworkPacket packet = new NetworkPacket(
            MessageType.KEY_NODE_ANNOUNCE,
            node.getNodeId(),
            node.getNickname(),
            node.incrementClock(),
            createOwnPeerInfo()
        );
        
        // Broadcast to own swarm
        node.broadcastToSwarm(packet);
        
        // Also send to all known key nodes from other swarms
        for (PeerInfo keyNode : knownKeyNodes.values()) {
            try {
                node.sendTo(packet, keyNode.getIpAddress(), keyNode.getPort());
            } catch (Exception e) {
                System.err.println("Failed to notify key node: " + keyNode.getNickname());
            }
        }
    }
    
    /**
     * Get current key node for our swarm
     */
    public PeerInfo getCurrentKeyNode() {
        return currentKeyNode;
    }
    
    /**
     * Get all known key nodes from other swarms
     */
    public Collection<PeerInfo> getKnownKeyNodes() {
        return new ArrayList<>(knownKeyNodes.values());
    }
    
    /**
     * Share known key nodes with swarm members
     */
    public void shareKeyNodes() {
        if (!node.isSwarmKey()) {
            return; // Only key nodes share this info
        }
        
        NetworkPacket packet = new NetworkPacket(
            MessageType.KEY_NODE_LIST,
            node.getNodeId(),
            node.getNickname(),
            node.incrementClock(),
            new ArrayList<>(knownKeyNodes.values())
        );
        
        node.broadcastToSwarm(packet);
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
        } catch (Exception e) {
            System.err.println("Failed to request key nodes: " + e.getMessage());
        }
    }
    
    private PeerInfo createOwnPeerInfo() {
        PeerInfo own = new PeerInfo(
            node.getNodeId(), 
            node.getNickname(), 
            node.getIpAddress(), 
            node.getPort(), 
            node.getSwarmId()
        );
        own.setSwarmKey(node.isSwarmKey());
        own.setJoinTime(joinTime);
        return own;
    }
    
    public long getJoinTime() {
        return joinTime;
    }
    
    public Collection<PeerInfo> getSwarmMembers() {
        return new ArrayList<>(swarmMembers.values());
    }
}
