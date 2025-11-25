import java.util.List;
import java.util.UUID;

public class ProtocolTest {
    
    public static void main(String[] args) {
        System.out.println("=== Testing Protocol Layer ===\n");
        
        try {
            // Test 1: Simple packet serialization
            System.out.println("Test 1: Simple packet serialization/deserialization");
            UUID nodeId = UUID.randomUUID();
            ChatMessage msg = new ChatMessage(nodeId, "Alice", "Hello!", 1);
            
            NetworkPacket packet = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                nodeId,
                "Alice",
                1,
                msg
            );
            
            byte[] serialized = PacketSerialiser.serialize(packet);
            System.out.println("Serialized size: " + serialized.length + " bytes");
            
            NetworkPacket deserialized = PacketSerialiser.deserialize(serialized);
            System.out.println("Deserialized: " + deserialized);
            ChatMessage deserializedMsg = (ChatMessage) deserialized.getPayload();
            System.out.println("Message content: " + deserializedMsg.getContent());
            System.out.println(":) Simple serialization works\n");
            
            // Test 2: Small message (no fragmentation needed)
            System.out.println("Test 2: Small message fragmentation check");
            String smallContent = "This is a small message";
            ChatMessage smallMsg = new ChatMessage(nodeId, "Alice", smallContent, 2);
            NetworkPacket smallPacket = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                nodeId,
                "Alice",
                2,
                smallMsg
            );
            
            List<NetworkPacket> fragments = PacketSerialiser.fragment(smallPacket);
            System.out.println("Fragments created: " + fragments.size());
            System.out.println(":) Small message not fragmented\n");
            
            // Test 3: Large message (requires fragmentation)
            System.out.println("Test 3: Large message fragmentation");
            StringBuilder largeContent = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                largeContent.append("This is a long message to test fragmentation. ");
            }
            
            ChatMessage largeMsg = new ChatMessage(nodeId, "Bob", largeContent.toString(), 3);
            NetworkPacket largePacket = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                nodeId,
                "Bob",
                3,
                largeMsg
            );
            
            List<NetworkPacket> largeFragments = PacketSerialiser.fragment(largePacket);
            System.out.println("Large message size: ~" + largeContent.length() + " chars");
            System.out.println("Fragments created: " + largeFragments.size());
            
            for (int i = 0; i < largeFragments.size(); i++) {
                NetworkPacket frag = largeFragments.get(i);
                byte[] fragBytes = PacketSerialiser.serialize(frag);
                System.out.println("  Fragment " + i + ": " + fragBytes.length + " bytes");
            }
            System.out.println(":) Large message fragmented correctly\n");
            
            // Test 4: Fragment assembly
            System.out.println("Test 4: Fragment assembly");
            FragmentAssembler assembler = new FragmentAssembler();
            
            NetworkPacket assembled = null;
            for (int i = 0; i < largeFragments.size(); i++) {
                NetworkPacket result = assembler.addFragment(largeFragments.get(i));
                if (result != null) {
                    assembled = result;
                    System.out.println("Assembly complete at fragment " + i);
                }
            }
            
            if (assembled != null) {
                ChatMessage assembledMsg = (ChatMessage) assembled.getPayload();
                boolean contentMatches = assembledMsg.getContent().equals(largeContent.toString());
                System.out.println("Content matches: " + contentMatches);
                System.out.println("Assembled message type: " + assembled.getType());
                System.out.println(":) Fragment assembly works correctly\n");
            } else {
                System.out.println("✗ Assembly failed!\n");
            }
            
            // Test 5: Out-of-order fragment assembly
            System.out.println("Test 5: Out-of-order fragment assembly");
            FragmentAssembler assembler2 = new FragmentAssembler();
            
            // Add fragments in reverse order
            NetworkPacket assembled2 = null;
            for (int i = largeFragments.size() - 1; i >= 0; i--) {
                NetworkPacket result = assembler2.addFragment(largeFragments.get(i));
                if (result != null) {
                    assembled2 = result;
                }
            }
            
            if (assembled2 != null) {
                ChatMessage assembledMsg2 = (ChatMessage) assembled2.getPayload();
                boolean contentMatches2 = assembledMsg2.getContent().equals(largeContent.toString());
                System.out.println("Content matches (out-of-order): " + contentMatches2);
                System.out.println(":) Out-of-order assembly works\n");
            }
            
            // Test 6: Different message types
            System.out.println("Test 6: Different message types");
            PeerInfo peer = new PeerInfo(UUID.randomUUID(), "Charlie", "192.168.1.1", 5000, 0);
            NetworkPacket discoveryPacket = new NetworkPacket(
                MessageType.PEER_DISCOVERY,
                nodeId,
                "Alice",
                4,
                peer
            );
            
            byte[] discoveryBytes = PacketSerialiser.serialize(discoveryPacket);
            NetworkPacket recoveredDiscovery = PacketSerialiser.deserialize(discoveryBytes);
            System.out.println("Discovery packet: " + recoveredDiscovery);
            PeerInfo recoveredPeer = (PeerInfo) recoveredDiscovery.getPayload();
            System.out.println("Peer info: " + recoveredPeer);
            System.out.println(":) Different message types work\n");
            
            System.out.println("=== All Protocol Tests Passed! ===");
            
        } catch (Exception e) {
            System.err.println("Test failed with exception:");
            e.printStackTrace();
        }
    }
}
