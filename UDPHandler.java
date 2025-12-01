import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

public class UDPHandler {
    
    private DatagramSocket socket;
    private int port;
    private boolean running;
    
    // Listener for incoming packets
    private PacketListener listener;
    
    // Thread for receiving packets
    private Thread receiverThread;
    
    // Thread pool for handling packets
    private ExecutorService executorService;
    
    // Buffer size for receiving
    private static final int BUFFER_SIZE = 65536; // Max UDP size
    
    public UDPHandler(int port, PacketListener listener) throws SocketException {
        this.port = port;
        this.listener = listener;
        this.socket = new DatagramSocket(port);
        this.running = false;
        this.executorService = Executors.newFixedThreadPool(4);
    }
    
    /**
     * Start listening for incoming packets
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        receiverThread = new Thread(this::receiveLoop);
        receiverThread.setDaemon(true);
        receiverThread.start();
        
        System.out.println("UDP Handler started on port " + port);
    }
    
    /**
     * Stop the UDP handler
     */
    public void stop() {
        running = false;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        System.out.println("UDP Handler stopped");
    }
    
    /**
     * Send a packet to a specific address
     */
    public void send(NetworkPacket packet, String address, int port) throws IOException {
        byte[] data = PacketSerialiser.serialize(packet);
        
        // Only check size for non-fragmented packets
        // Fragmented packets are already split and should be sent as-is
        if (!packet.isFragmented() && data.length > PacketSerialiser.MAX_PACKET_SIZE) {
            throw new IOException("Packet too large: " + data.length + " bytes");
        }
        
        if (NetworkConditions.shouldDropOutbound()) {
            System.out.println("[Simulated] Dropped outbound packet to " + address + ":" + port);
            return;
        }
        
        InetAddress inetAddress = InetAddress.getByName(address);
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length, inetAddress, port);
        
        socket.send(datagramPacket);
    }
    
    /**
     * Send a packet with automatic fragmentation
     */
    public void sendWithFragmentation(NetworkPacket packet, String address, int port) throws IOException {
        var fragments = PacketSerialiser.fragment(packet);
        
        if (packet.getType() == MessageType.FILE_TRANSFER || fragments.size() > 1) {
            System.out.println("[FRAG-DEBUG] Sending " + packet.getType() + " to " + address + ":" + port);
            System.out.println("[FRAG-DEBUG] Total fragments: " + fragments.size());
        }
        
        int sentCount = 0;
        for (NetworkPacket fragment : fragments) {
            send(fragment, address, port);
            sentCount++;
            
            if (fragments.size() > 1 && (sentCount % 10 == 0 || sentCount == fragments.size())) {
                System.out.println("[FRAG-DEBUG] Sent fragment " + sentCount + "/" + fragments.size() + " to " + address + ":" + port);
            }
            
            // Small delay between fragments to avoid overwhelming receiver
            if (fragments.size() > 1) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        if (fragments.size() > 1) {
            System.out.println("[FRAG-DEBUG] All " + fragments.size() + " fragments sent to " + address + ":" + port);
        }
    }
    
    /**
     * Broadcast a packet to multiple peers
     */
    public void broadcast(NetworkPacket packet, Iterable<PeerInfo> peers) {
        int peerCount = 0;
        for (PeerInfo peer : peers) {
            peerCount++;
            if (packet.getType() == MessageType.FILE_TRANSFER) {
                System.out.println("[BROADCAST-DEBUG] Sending " + packet.getType() + " to peer: " + peer.getNickname() + " @ " + peer.getIpAddress() + ":" + peer.getPort());
            }
            try {
                sendWithFragmentation(packet, peer.getIpAddress(), peer.getPort());
            } catch (IOException e) {
                System.err.println("Failed to send to " + peer.getNickname() + ": " + e.getMessage());
            }
        }
        if (packet.getType() == MessageType.FILE_TRANSFER && peerCount == 0) {
            System.out.println("[BROADCAST-DEBUG] WARNING: No peers to broadcast FILE_TRANSFER to!");
        }
    }
    
    /**
     * Main receive loop
     */
    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        
        while (running) {
            try {
                DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagramPacket);
                
                // Extract data
                byte[] data = new byte[datagramPacket.getLength()];
                System.arraycopy(datagramPacket.getData(), 0, data, 0, datagramPacket.getLength());
                
                // Get sender info
                String senderAddress = datagramPacket.getAddress().getHostAddress();
                int senderPort = datagramPacket.getPort();

                if (NetworkConditions.shouldDropInbound()) {
                    System.out.println("[Simulated] Dropped inbound packet from " + senderAddress + ":" + senderPort);
                    continue;
                }
                
                // Handle packet in thread pool
                if (data.length > 1000) {
                    System.out.println("[UDP-DEBUG] Received large packet (" + data.length + " bytes) from " + senderAddress + ":" + senderPort);
                }
                executorService.submit(() -> handleReceivedPacket(data, senderAddress, senderPort));
                
            } catch (SocketException e) {
                if (running) {
                    System.err.println("Socket error: " + e.getMessage());
                }
                // Socket closed, exit loop
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error receiving packet: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Handle a received packet
     */
    private void handleReceivedPacket(byte[] data, String senderAddress, int senderPort) {
        try {
            NetworkPacket packet = PacketSerialiser.deserialize(data);
            
            // Notify listener
            if (listener != null) {
                listener.onPacketReceived(packet, senderAddress, senderPort);
            }
            
        } catch (Exception e) {
            System.err.println("Error handling packet from " + senderAddress + ":" + senderPort);
            e.printStackTrace();
        }
    }
    
    public int getPort() {
        return port;
    }
    
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Interface for listening to incoming packets
     */
    public interface PacketListener {
        void onPacketReceived(NetworkPacket packet, String senderAddress, int senderPort);
    }
}
