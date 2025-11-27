import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DiscoveryHandler {
    
    private static final int DISCOVERY_BASE_PORT = 6000;
    private static final int DISCOVERY_PORT_RANGE = 100; // 6000-6099
    private static final String BROADCAST_ADDRESS = "255.255.255.255";
    private static final long ROOM_TIMEOUT = 30000; // 30 seconds
    private static final long BROADCAST_INTERVAL = 5000; // 5 seconds (reduced from 10)
    
    private DatagramSocket socket;
    private Thread listenerThread;
    private Thread broadcasterThread;
    private boolean running;
    private int discoveryPort; // Actual port we're using
    
    private UUID roomId;
    private String roomName;
    private String nodeAddress;
    private int nodePort;
    private volatile boolean networkBroadcastWarningLogged;
    private volatile boolean broadcastEnabled = true;
    private CopyOnWriteArrayList<InetSocketAddress> unicastTargets = new CopyOnWriteArrayList<>();
    
    private Map<UUID, RoomInfo> discoveredRooms;
    private DiscoveryListener listener;
    
    public DiscoveryHandler(UUID roomId, String roomName, String nodeAddress, int nodePort, DiscoveryListener listener) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.nodeAddress = nodeAddress;
        this.nodePort = nodePort;
        this.listener = listener;
        this.discoveredRooms = new ConcurrentHashMap<>();
        this.running = false;
        this.discoveryPort = calculatePreferredPort(nodePort);
    }
    
    /**
     * Start discovery - listen for broadcasts and send our own
     */
    public void start() {
        if (running) {
            return;
        }
        
        // Bind to a discovery port within shared range
        boolean bound = false;
        int preferredOffset = Math.floorMod(discoveryPort - DISCOVERY_BASE_PORT, DISCOVERY_PORT_RANGE);
        for (int i = 0; i < DISCOVERY_PORT_RANGE; i++) {
            int offset = (preferredOffset + i) % DISCOVERY_PORT_RANGE;
            int tryPort = DISCOVERY_BASE_PORT + offset;
            try {
                socket = new DatagramSocket(null);
                socket.setBroadcast(true);
                socket.setReuseAddress(true);
                socket.setSoTimeout(1000); // 1 second timeout for receives
                socket.bind(new InetSocketAddress(tryPort));
                discoveryPort = tryPort;
                System.out.println("Bound to discovery port " + discoveryPort);
                bound = true;
                break;
            } catch (SocketException e) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
        
        if (!bound) {
            System.err.println("Could not bind to discovery port range " + DISCOVERY_BASE_PORT + "-" + (DISCOVERY_BASE_PORT + DISCOVERY_PORT_RANGE - 1));
            System.err.println("Room discovery will not be available, but manual connections still work.");
            return;
        }
        
        running = true;
        
        // Start listener thread
        listenerThread = new Thread(this::listenForBroadcasts);
        listenerThread.setDaemon(true);
        listenerThread.start();
        
        // Start broadcaster thread
        broadcasterThread = new Thread(this::broadcastPresence);
        broadcasterThread.setDaemon(true);
        broadcasterThread.start();
        
        System.out.println("Discovery handler started on port " + discoveryPort);
        
        // Send immediate broadcast on startup to announce presence
        // This ensures existing nodes discover us right away
        new Thread(() -> {
            try {
                Thread.sleep(500); // Small delay to ensure listener is ready
                sendBroadcast();
                System.out.println("Sent initial discovery broadcast");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * Stop discovery
     */
    public void stop() {
        running = false;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        System.out.println("Discovery handler stopped");
    }
    
    /**
     * Listen for broadcast messages from other nodes
     */
    private void listenForBroadcasts() {
        byte[] buffer = new byte[8192];
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                // Extract and parse data
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                
                handleDiscoveryPacket(data);
                
            } catch (SocketTimeoutException e) {
                // Timeout is normal, just continue listening
                continue;
            } catch (SocketException e) {
                if (running) {
                    System.err.println("Discovery socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error receiving discovery packet: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast our presence periodically
     */
    private void broadcastPresence() {
        // Send rapid initial broadcasts for quick discovery
        for (int i = 0; i < 3; i++) {
            try {
                if (broadcastEnabled) {
                    sendBroadcast();
                }
                Thread.sleep(1000); // 1 second between initial broadcasts
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        // Then settle into normal broadcast interval
        while (running) {
            try {
                if (broadcastEnabled) {
                    sendBroadcast();
                }
                Thread.sleep(BROADCAST_INTERVAL);
                
                // Clean up old rooms
                cleanupOldRooms();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Send a broadcast packet announcing this room
     */
    private void sendBroadcast() {
        if (!broadcastEnabled) {
            return;
        }
        try {
            // Get current member count from listener
            int memberCount = listener != null ? listener.getCurrentMemberCount() : 1;
            
            RoomInfo info = new RoomInfo(roomId, roomName, nodeAddress, nodePort, memberCount);
            
            // Serialize
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(baos);
            oos.writeObject(info);
            oos.flush();
            byte[] data = baos.toByteArray();
            
            // Broadcast to localhost first (for same-machine testing)
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            
            for (int offset = 0; offset < DISCOVERY_PORT_RANGE; offset++) {
                int targetPort = DISCOVERY_BASE_PORT + offset;
                if (targetPort == discoveryPort) {
                    continue;
                }
                DatagramPacket packet = new DatagramPacket(data, data.length, localhost, targetPort);
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    // Port not listening, that's fine
                }
            }
            
            // Also broadcast to network (for cross-machine discovery)
            try {
                InetAddress broadcastAddr = InetAddress.getByName(BROADCAST_ADDRESS);
                for (int offset = 0; offset < DISCOVERY_PORT_RANGE; offset++) {
                    int targetPort = DISCOVERY_BASE_PORT + offset;
                    DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddr, targetPort);
                    try {
                        socket.send(packet);
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            } catch (IOException e) {
                if (running && !networkBroadcastWarningLogged) {
                    networkBroadcastWarningLogged = true;
                    System.err.println("Network broadcast unavailable: " + e.getMessage() +
                                       " (continuing with localhost discovery)");
                }
            }

            // Send directly to any configured unicast targets (cross-network seeds)
            for (InetSocketAddress target : unicastTargets) {
                DatagramPacket packet = new DatagramPacket(data, data.length, target);
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    System.err.println("Failed to reach discovery seed " + target + ": " + e.getMessage());
                }
            }
            
        } catch (IOException e) {
            if (running) {
                System.err.println("Failed to send discovery broadcast: " + e.getMessage());
            }
        }
    }

    public void setBroadcastEnabled(boolean enabled) {
        this.broadcastEnabled = enabled;
    }

    public void triggerImmediateBroadcast() {
        if (broadcastEnabled) {
            try {
                sendBroadcast();
            } catch (Exception e) {
                // Ignore trigger failures
            }
        }
    }
    
    public void addUnicastTarget(String address, int port) {
        try {
            InetSocketAddress target = new InetSocketAddress(address, port);
            if (!unicastTargets.contains(target)) {
                unicastTargets.add(target);
                System.out.println("Added discovery seed " + target);
            }
        } catch (Exception e) {
            System.err.println("Invalid discovery seed: " + e.getMessage());
        }
    }

    public List<InetSocketAddress> getUnicastTargets() {
        return new ArrayList<>(unicastTargets);
    }

    /**
     * Handle received discovery packet
     */
    private void handleDiscoveryPacket(byte[] data) {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais);
            RoomInfo room = (RoomInfo) ois.readObject();
            
            // Add or update room
            RoomInfo existing = discoveredRooms.get(room.getRoomId());
            if (existing == null) {
                discoveredRooms.put(room.getRoomId(), room);
                System.out.println("Discovered new room: " + room.getRoomName() + " @ " + 
                                 room.getSeedNodeAddress() + ":" + room.getSeedNodePort());
            } else {
                existing.setMemberCount(room.getMemberCount());
                existing.updateLastSeen();
            }
            
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error parsing discovery packet: " + e.getMessage());
        }
    }
    
    /**
     * Remove rooms that haven't been seen recently
     */
    private void cleanupOldRooms() {
        long now = System.currentTimeMillis();
        discoveredRooms.entrySet().removeIf(entry -> 
            now - entry.getValue().getLastSeen() > ROOM_TIMEOUT
        );
    }
    
    /**
     * Get list of discovered rooms
     */
    public Collection<RoomInfo> getDiscoveredRooms() {
        cleanupOldRooms();
        return new ArrayList<>(discoveredRooms.values());
    }
    
    /**
     * Update our room name
     */
    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }
    
    public boolean isRunning() {
        return running;
    }
    
    private int calculatePreferredPort(int nodePort) {
        return DISCOVERY_BASE_PORT + Math.floorMod(nodePort, DISCOVERY_PORT_RANGE);
    }
    
    /**
     * Interface for getting current state
     */
    public interface DiscoveryListener {
        int getCurrentMemberCount();
    }
}
