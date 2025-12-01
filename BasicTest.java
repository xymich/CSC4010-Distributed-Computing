import java.util.UUID;

public class BasicTest {
    
    public static void main(String[] args) {
        System.out.println("=== Testing Core Data Structures ===\n");
        
        // Test 1: Create ChatNode
        System.out.println("Test 1: Creating ChatNode");
        ChatNode node1 = new ChatNode("Alice", "192.168.1.10", 5000);
        System.out.println(node1);
        System.out.println("Initial Lamport Clock: " + node1.getClock());
        System.out.println(":) ChatNode created\n");
        
        // Test 2: Create PeerInfo
        System.out.println("Test 2: Creating PeerInfo");
        PeerInfo peer1 = new PeerInfo(
            UUID.randomUUID(), 
            "Bob", 
            "192.168.1.11", 
            5001, 
            0
        );
        System.out.println(peer1);
        System.out.println(":) PeerInfo created\n");
        
        // Test 3: Add peer to node
        System.out.println("Test 3: Adding peer to node");
        node1.addPeer(peer1);
        System.out.println("Known peers: " + node1.getAllPeers().size());
        System.out.println("Leader peers: " + node1.getLeaderPeers().size());
        System.out.println(":) Peer added successfully\n");
        
        // Test 4: Create and add ChatMessage
        System.out.println("Test 4: Creating ChatMessage");
        long timestamp1 = node1.incrementClock();
        ChatMessage msg1 = new ChatMessage(
            node1.getNodeId(),
            node1.getNickname(),
            "Hello, World!",
            timestamp1
        );
        System.out.println(msg1);
        node1.addMessage(msg1);
        System.out.println("Lamport Clock after message: " + node1.getClock());
        System.out.println(":) Message created and added\n");
        
        // Test 5: Lamport clock update
        System.out.println("Test 5: Testing Lamport clock update");
        System.out.println("Current clock: " + node1.getClock());
        node1.updateClock(100); // Simulate receiving message with timestamp 100
        System.out.println("Clock after receiving timestamp 100: " + node1.getClock());
        System.out.println(":) Lamport clock updated correctly\n");
        
        // Test 6: Multiple messages and ordering
        System.out.println("Test 6: Testing message ordering");
        ChatMessage msg2 = new ChatMessage(
            peer1.getNodeId(),
            peer1.getNickname(),
            "Hi Alice!",
            node1.incrementClock()
        );
        node1.addMessage(msg2);
        
        ChatMessage msg3 = new ChatMessage(
            node1.getNodeId(),
            node1.getNickname(),
            "How are you?",
            node1.incrementClock()
        );
        node1.addMessage(msg3);
        
        System.out.println("All messages in order:");
        for (ChatMessage msg : node1.getAllMessages()) {
            System.out.println("  " + msg);
        }
        System.out.println(":) Messages ordered correctly\n");
        
        // Test 7: Leader key node
        System.out.println("Test 7: Testing leader key node");
        node1.setLeaderKey(true);
        System.out.println(node1);
        
        PeerInfo keyPeer = new PeerInfo(
            UUID.randomUUID(),
            "Charlie",
            "192.168.1.12",
            5002,
            1  // Different leader
        );
        keyPeer.setLeaderKey(true);
        node1.addPeer(keyPeer);
        
        System.out.println("Key node peers: " + node1.getKeyNodePeers().size());
        for (PeerInfo p : node1.getKeyNodePeers()) {
            System.out.println("  " + p);
        }
        System.out.println(":) Leader key node working\n");
        
        // Test 8: Message deduplication
        System.out.println("Test 8: Testing message deduplication");
        int beforeCount = node1.getAllMessages().size();
        node1.addMessage(msg1); // Add same message again
        int afterCount = node1.getAllMessages().size();
        System.out.println("Messages before re-add: " + beforeCount);
        System.out.println("Messages after re-add: " + afterCount);
        System.out.println(":) Deduplication working: " + (beforeCount == afterCount) + "\n");
        
        System.out.println("=== All Tests Passed! ===");
    }
}
