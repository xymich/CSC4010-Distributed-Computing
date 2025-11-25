import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UDPTest {
    
    public static void main(String[] args) {
        System.out.println("=== Testing UDP Layer ===\n");
        
        try {
            // Test 1: Basic send and receive
            System.out.println("Test 1: Basic send and receive");
            CountDownLatch latch1 = new CountDownLatch(1);
            
            // Create receiver
            UDPHandler receiver = new UDPHandler(5001, (packet, address, port) -> {
                System.out.println("Received: " + packet);
                ChatMessage msg = (ChatMessage) packet.getPayload();
                System.out.println("Message content: " + msg.getContent());
                latch1.countDown();
            });
            receiver.start();
            
            // Give receiver time to start
            Thread.sleep(100);
            
            // Create sender
            UDPHandler sender = new UDPHandler(5002, (packet, address, port) -> {});
            sender.start();
            
            // Send a message
            UUID senderId = UUID.randomUUID();
            ChatMessage testMsg = new ChatMessage(senderId, "Alice", "Hello UDP!", 1);
            NetworkPacket testPacket = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                senderId,
                "Alice",
                1,
                testMsg
            );
            
            sender.send(testPacket, "127.0.0.1", 5001);
            
            // Wait for receive
            boolean received = latch1.await(2, TimeUnit.SECONDS);
            System.out.println(":) Message " + (received ? "received" : "NOT received") + "\n");
            
            // Test 2: Large message with fragmentation
            System.out.println("Test 2: Large message with fragmentation");
            CountDownLatch latch2 = new CountDownLatch(1);
            FragmentAssembler assembler = new FragmentAssembler();
            
            UDPHandler receiver2 = new UDPHandler(5003, (packet, address, port) -> {
                try {
                    NetworkPacket assembled = assembler.addFragment(packet);
                    if (assembled != null) {
                        System.out.println("Assembled complete message!");
                        ChatMessage msg = (ChatMessage) assembled.getPayload();
                        System.out.println("Content length: " + msg.getContent().length() + " chars");
                        latch2.countDown();
                    } else {
                        System.out.println("Received fragment, waiting for more...");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            receiver2.start();
            
            Thread.sleep(100);
            
            UDPHandler sender2 = new UDPHandler(5004, (packet, address, port) -> {});
            sender2.start();
            
            // Create large message
            StringBuilder largeContent = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                largeContent.append("Large message test content. ");
            }
            
            ChatMessage largeMsg = new ChatMessage(senderId, "Bob", largeContent.toString(), 2);
            NetworkPacket largePacket = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                senderId,
                "Bob",
                2,
                largeMsg
            );
            
            sender2.sendWithFragmentation(largePacket, "127.0.0.1", 5003);
            
            boolean receivedLarge = latch2.await(5, TimeUnit.SECONDS);
            System.out.println(":) Large message " + (receivedLarge ? "received" : "NOT received") + "\n");
            
            // Test 3: Multiple rapid messages
            System.out.println("Test 3: Multiple rapid messages");
            CountDownLatch latch3 = new CountDownLatch(5);
            
            UDPHandler receiver3 = new UDPHandler(5005, (packet, address, port) -> {
                System.out.println("  Received message #" + packet.getLamportTimestamp());
                latch3.countDown();
            });
            receiver3.start();
            
            Thread.sleep(100);
            
            UDPHandler sender3 = new UDPHandler(5006, (packet, address, port) -> {});
            sender3.start();
            
            // Send 5 messages rapidly
            for (int i = 0; i < 5; i++) {
                ChatMessage rapidMsg = new ChatMessage(senderId, "Charlie", "Message " + i, i);
                NetworkPacket rapidPacket = new NetworkPacket(
                    MessageType.CHAT_MESSAGE,
                    senderId,
                    "Charlie",
                    i,
                    rapidMsg
                );
                sender3.send(rapidPacket, "127.0.0.1", 5005);
                Thread.sleep(50);
            }
            
            boolean receivedAll = latch3.await(3, TimeUnit.SECONDS);
            System.out.println(":) Rapid messages " + (receivedAll ? "all received" : "some missing") + "\n");
            
            // Test 4: Broadcast
            System.out.println("Test 4: Broadcast to multiple peers");
            CountDownLatch latch4a = new CountDownLatch(1);
            CountDownLatch latch4b = new CountDownLatch(1);
            
            UDPHandler receiver4a = new UDPHandler(5007, (packet, address, port) -> {
                System.out.println("  Peer A received broadcast");
                latch4a.countDown();
            });
            receiver4a.start();
            
            UDPHandler receiver4b = new UDPHandler(5008, (packet, address, port) -> {
                System.out.println("  Peer B received broadcast");
                latch4b.countDown();
            });
            receiver4b.start();
            
            Thread.sleep(100);
            
            UDPHandler sender4 = new UDPHandler(5009, (packet, address, port) -> {});
            sender4.start();
            
            // Create peer list
            var peers = java.util.List.of(
                new PeerInfo(UUID.randomUUID(), "PeerA", "127.0.0.1", 5007, 0),
                new PeerInfo(UUID.randomUUID(), "PeerB", "127.0.0.1", 5008, 0)
            );
            
            ChatMessage broadcastMsg = new ChatMessage(senderId, "Broadcaster", "Broadcast test", 10);
            NetworkPacket broadcastPacket = new NetworkPacket(
                MessageType.CHAT_MESSAGE,
                senderId,
                "Broadcaster",
                10,
                broadcastMsg
            );
            
            sender4.broadcast(broadcastPacket, peers);
            
            boolean receivedBroadcastA = latch4a.await(2, TimeUnit.SECONDS);
            boolean receivedBroadcastB = latch4b.await(2, TimeUnit.SECONDS);
            System.out.println(":) Broadcast " + 
                (receivedBroadcastA && receivedBroadcastB ? "successful" : "incomplete") + "\n");
            
            // Cleanup
            System.out.println("Cleaning up...");
            receiver.stop();
            sender.stop();
            receiver2.stop();
            sender2.stop();
            receiver3.stop();
            sender3.stop();
            receiver4a.stop();
            receiver4b.stop();
            sender4.stop();
            
            Thread.sleep(500);
            
            System.out.println("\n=== All UDP Tests Completed! ===");
            
        } catch (Exception e) {
            System.err.println("Test failed with exception:");
            e.printStackTrace();
        }
    }
}
