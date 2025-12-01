import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleChatClient implements MessageStatusListener {
    
    private static ChatNode node;
    private static Scanner scanner;
    private static boolean running = true;
    private static String advertisedIp;
    private static volatile boolean robotEnabled;
    private static Thread robotThread;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024; // 2 MB safety limit
    private static final String[] ROBOT_MESSAGES = {
        "Hello.",
        "Robot status: still online.",
        "Anyone else love Lamport clocks?",
        "Latency feels nice from here.",
        "Robot message: 00111000010100101001"
    };
    private static final long ROBOT_MIN_DELAY_MS = 2000;
    private static final long ROBOT_MAX_DELAY_MS = 6000;
    private static final long MESSAGE_ACK_TIMEOUT_MS = 3000;  // Wait 3 seconds for ACK before showing as failed
    
    // Pending message tracking for CLI: messageId -> (display text, send timestamp)
    private static Map<UUID, PendingMessage> pendingMessages = new ConcurrentHashMap<>();
    private static SimpleChatClient instance = new SimpleChatClient();
    
    // Helper class to track pending message info
    private static class PendingMessage {
        final String displayText;
        final long sentTime;
        PendingMessage(String displayText) {
            this.displayText = displayText;
            this.sentTime = System.currentTimeMillis();
        }
    }
    
    /**
     * MessageStatusListener implementation - display message when delivery confirmed
     */
    @Override
    public void onMessageStatusChanged(UUID messageId, boolean delivered) {
        PendingMessage pending = pendingMessages.remove(messageId);
        if (pending != null && delivered) {
            // Message confirmed - display it
            System.out.println(pending.displayText);
        }
    }
    
    /**
     * Check for pending messages that have timed out and display them with failure indicator
     */
    private static void checkPendingMessageTimeouts() {
        long now = System.currentTimeMillis();
        var iterator = pendingMessages.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingMessage pending = entry.getValue();
            if (now - pending.sentTime > MESSAGE_ACK_TIMEOUT_MS) {
                // Timed out - show message with failure indicator
                System.out.println(pending.displayText + " [UNCONFIRMED]");
                iterator.remove();
            }
        }
    }
    
    public static void main(String[] args) {
        scanner = new Scanner(System.in);
        
        System.out.println("=== P2P Distributed Chat System ===\n");
        
        // Get user input with validation
        String nickname = getNickname();
        advertisedIp = chooseAdvertisedIp();
        
        // Retry loop for port selection
        while (true) {
            int port = getPort();
            
            // Create node with error handling
            try {
                node = new ChatNode(nickname, advertisedIp, port);
                node.addMessageStatusListener(instance);  // Register for delivery status updates
                node.start();
                break; // Success! Exit the retry loop
            } catch (Exception e) {
                System.err.println("Failed to start node: " + e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
                    System.err.println("Port " + port + " is already in use. Please try a different port.\n");
                } else {
                    System.err.println("Unexpected error. Please try again.\n");
                }
                // Loop continues, asking for a new port
            }
        }
        
        System.out.println("\nYour node info:");
        System.out.println(node);
        
        // Check if joining existing network
        System.out.print("\nJoin existing network? [Y]/n: ");
        String joinChoice = scanner.nextLine().trim();
        
        if (isYes(joinChoice)) {
            node.prepareForJoin();
            connectToNetwork();
        } else {
            node.ensureSeedKey();
            System.out.println("\nStarted as seed node. Others can join at: " + advertisedIp + ":" + node.getPort());
        }
        
        showCommands();
        
        // Main chat loop
        chatLoop();
        
        scanner.close();
    }
    
    private static String getNickname() {
        while (true) {
            System.out.print("Enter your nickname: ");
            String nickname = scanner.nextLine().trim();
            
            if (nickname.isEmpty()) {
                System.out.println("Nickname cannot be empty!");
                continue;
            }
            
            if (nickname.contains(" ")) {
                System.out.println("Nickname cannot contain spaces!");
                continue;
            }
            
            if (nickname.startsWith("/")) {
                System.out.println("Nickname cannot start with '/'!");
                continue;
            }
            
            return nickname;
        }
    }
    
    private static String chooseAdvertisedIp() {
        String detected = NetworkUtils.detectBestAddress();
        System.out.println("Detected local IP for advertising: " + detected);
        System.out.println("Set CHAT_ADVERTISE_IP env variable or enter a value to override.");
        while (true) {
            System.out.print("Advertise which IP [" + detected + "]: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                return detected;
            }
            if (isValidIP(input)) {
                return input;
            }
            System.out.println("Invalid IP. Please enter a valid IPv4 address.");
        }
    }
    
    private static int getPort() {
        while (true) {
            System.out.print("Enter your port (1024-65535): ");
            try {
                String input = scanner.nextLine().trim();
                
                // Check for command
                if (input.startsWith("/")) {
                    System.out.println("Cannot use commands here. Please enter a port number.");
                    continue;
                }
                
                int port = Integer.parseInt(input);
                
                if (port < 1024 || port > 65535) {
                    System.out.println("Port must be between 1024 and 65535!");
                    continue;
                }
                
                return port;
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number! Please enter a number.");
            }
        }
    }
    
    private static void connectToNetwork() {
        try {
            Thread.sleep(500); // Give discovery a moment to populate
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        node.prepareForJoin();
        
        while (true) {
            var rooms = new ArrayList<>(node.getDiscoveredRooms());
            System.out.println("\nConnecting to network...");
            if (rooms.isEmpty()) {
                System.out.println("No discovered rooms yet. Enter an IP address manually or wait for broadcasts.");
            } else {
                System.out.println("Discovered rooms:");
                for (int i = 0; i < rooms.size(); i++) {
                    System.out.println("  " + (i + 1) + ". " + rooms.get(i));
                }
            }
            System.out.print("Enter room number, IP address, or 'n' to cancel: ");
            String selection = scanner.nextLine().trim();
            
            if (selection.equalsIgnoreCase("n")) {
                node.ensureSeedKey();
                System.out.println("Connection cancelled. Started as seed node.");
                return;
            }
            
            if (selection.startsWith("/")) {
                handleCommand(selection);
                continue;
            }
            
            String peerIp;
            int peerPort;
            
            if (selection.contains(".")) {
                // Treat input as IP address
                if (!isValidIP(selection)) {
                    System.out.println("Invalid IP address! Please enter a valid IPv4 address (e.g., 127.0.0.1)");
                    continue;
                }
                Integer manualPort = promptForPeerPort();
                if (manualPort == null) {
                    node.ensureSeedKey();
                    System.out.println("Connection cancelled. Started as seed node.");
                    return;
                }
                peerIp = selection;
                peerPort = manualPort;
            } else {
                if (rooms.isEmpty()) {
                    System.out.println("No rooms available. Enter an IP address instead.");
                    continue;
                }
                try {
                    int roomNumber = Integer.parseInt(selection);
                    if (roomNumber < 1 || roomNumber > rooms.size()) {
                        System.out.println("Invalid room number. Use /rooms to refresh the list.");
                        continue;
                    }
                    RoomInfo room = rooms.get(roomNumber - 1);
                    peerIp = room.getSeedNodeAddress();
                    peerPort = room.getSeedNodePort();
                    System.out.println("Attempting to join room \"" + room.getRoomName() + "\" @ " + peerIp + ":" + peerPort + "...");
                } catch (NumberFormatException e) {
                    System.out.println("Invalid selection. Enter a room number or an IP address.");
                    continue;
                }
            }
            
            try {
                node.joinNetwork(peerIp, peerPort);
                Thread.sleep(1000); // Give time for join to complete
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Integer promptForPeerPort() {
        while (true) {
            System.out.print("Enter peer port (or 'n' to cancel): ");
            String portInput = scanner.nextLine().trim();
            
            if (portInput.equalsIgnoreCase("n")) {
                return null;
            }
            
            if (portInput.startsWith("/")) {
                handleCommand(portInput);
                continue;
            }
            
            try {
                int peerPort = Integer.parseInt(portInput);
                if (peerPort < 1024 || peerPort > 65535) {
                    System.out.println("Port must be between 1024 and 65535!");
                    continue;
                }
                return peerPort;
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number!");
            }
        }
    }
    
    private static boolean isValidIP(String ip) {
        try {
            // Must not be empty
            if (ip.isEmpty()) {
                return false;
            }
            
            // Remove any port numbers if user accidentally included them
            if (ip.contains(":")) {
                System.out.println("Note: Don't include the port in the IP address. Enter them separately.");
                return false;
            }
            
            // Must start with a digit
            if (!Character.isDigit(ip.charAt(0))) {
                System.out.println("IP address must start with a number.");
                return false;
            }
            
            // Check for invalid characters (letters, symbols except dots)
            for (char c : ip.toCharArray()) {
                if (!Character.isDigit(c) && c != '.') {
                    System.out.println("IP address can only contain numbers and dots.");
                    return false;
                }
            }
            
            // Validate using InetAddress
            InetAddress.getByName(ip);
            
            // Check if it's IPv4 format (4 parts)
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            
            // Validate each octet
            for (String part : parts) {
                if (part.isEmpty()) {
                    return false;
                }
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean isYes(String input) {
        // Empty or "y" or "Y" = yes
        // Anything else = no
        return input.isEmpty() || input.equalsIgnoreCase("y");
    }
    
    private static void showCommands() {
        System.out.println("\n=== Commands ===");
        System.out.println("/peers          - Show known peers in current room");
        System.out.println("/messages       - Show all messages");
        System.out.println("/status         - Show node status");
        System.out.println("/rooms          - List available chat rooms on network");
        System.out.println("/join <number>  - Join a discovered room by number");
        System.out.println("/newroom        - Create a new room as seed node");
        System.out.println("/disconnect     - Disconnect from current network");
        System.out.println("/reconnect      - Reconnect to a network");
        System.out.println("/roomname <name> - Rename your room");
        System.out.println("/resync         - Rebuild chat history from peers");
        System.out.println("/resynclocal    - Reload chat history from local log file");
        System.out.println("/sendfile <path> - Broadcast a file to the room");
        System.out.println("/clear          - Clear the local console view");
        System.out.println("/seed <ip> <port> - Add a remote discovery seed (unicast)");
        System.out.println("/seeds         - Show configured discovery seeds");
        System.out.println("/netloss [0-100] - Simulate packet loss percentage");
        System.out.println("/robot          - Toggle automated robot chatter");
        System.out.println("/help           - Show this help message");
        System.out.println("/quit           - Exit application");
        System.out.println("\nType a message and press Enter to send\n");
    }
    
    private static void chatLoop() {
        while (running) {
            try {
                // Check for timed out pending messages
                checkPendingMessageTimeouts();
                
                if (!scanner.hasNextLine()) {
                    break;
                }
                
                String input = scanner.nextLine();
                
                // Skip empty input
                if (input.trim().isEmpty()) {
                    continue;
                }
                
                // Handle commands
                if (input.startsWith("/")) {
                    if (!handleCommand(input.trim())) {
                        break; // Exit if /quit
                    }
                } else {
                    // Send as chat message
                    if (node != null && node.isRunning()) {
                        UUID messageId = node.sendChatMessage(input);
                        if (messageId != null) {
                            String displayText = "[" + node.getClock() + "] " + node.getNickname() + ": " + input;
                            if (node.isMessagePending(messageId)) {
                                // Store as pending - will display when ACK received or timeout
                                pendingMessages.put(messageId, new PendingMessage(displayText));
                            } else {
                                // No peers - immediately show (delivered to self)
                                System.out.println(displayText);
                            }
                        }
                    } else {
                        System.out.println("Error: Not connected to network. Use /reconnect to join.");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in chat loop: " + e.getMessage());
                break;
            }
        }
    }
    
    private static boolean handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        
        switch (command) {
            case "/quit":
                stopRobot(true);
                if (node != null) {
                    node.stop();
                }
                System.out.println("Goodbye!");
                running = false;
                return false;
                
            case "/peers":
                showPeers();
                break;
                
            case "/messages":
                showMessages();
                break;
                
            case "/status":
                showStatus();
                break;
                
            case "/rooms":
                showRooms();
                break;
                
            case "/join":
                if (parts.length > 1) {
                    joinRoomByNumber(parts[1]);
                } else {
                    System.out.println("Usage: /join <room_number>");
                    System.out.println("First use /rooms to see available rooms");
                }
                break;
            case "/roomname":
                if (parts.length > 1) {
                    renameRoom(parts[1].trim());
                } else {
                    System.out.println("Usage: /roomname <new name>");
                }
                break;
            case "/newroom":
                createNewRoom();
                break;
            case "/resync":
                rebuildHistory();
                break;
            case "/resynclocal":
                reloadHistoryFromLog();
                break;
            case "/seed":
                addDiscoverySeed(parts.length > 1 ? parts[1].trim() : "");
                break;
            case "/seeds":
                showDiscoverySeeds();
                break;
            case "/netloss":
                if (parts.length > 1) {
                    configureNetworkLoss(parts[1].trim());
                } else {
                    showNetworkLoss();
                }
                break;
            case "/sendfile":
                if (parts.length > 1) {
                    sendFile(parts[1].trim());
                } else {
                    System.out.println("Usage: /sendfile <path>");
                }
                break;
            case "/robot":
                toggleRobot();
                break;
                
            case "/disconnect":
                disconnect();
                break;
                
            case "/reconnect":
                reconnect();
                break;
            case "/clear":
                clearConsole();
                break;
                
            case "/help":
                showCommands();
                break;
                
            default:
                System.out.println("Unknown command: " + command);
                System.out.println("Type /help to see available commands.");
                break;
        }
        
        return true;
    }
    
    private static void showPeers() {
        if (node == null) return;
        
        System.out.println("\n=== Known Peers (" + node.getAllPeers().size() + ") ===");
        if (node.getAllPeers().isEmpty()) {
            System.out.println("  No peers connected");
        } else {
            for (var peer : node.getAllPeers()) {
                System.out.println("  " + peer);
            }
        }
        System.out.println();
    }
    
    private static void showMessages() {
        if (node == null) return;
        
        System.out.println("\n=== Message History (" + node.getAllMessages().size() + ") ===");
        if (node.getAllMessages().isEmpty()) {
            System.out.println("  No messages yet");
        } else {
            for (var msg : node.getAllMessages()) {
                System.out.println("  " + msg);
            }
        }
        System.out.println();
    }
    
    private static void showStatus() {
        if (node == null) return;
        
        System.out.println("\n=== Node Status ===");
        System.out.println(node);
        System.out.println("Running: " + node.isRunning());
        System.out.println("Lamport Clock: " + node.getClock());
        System.out.println("Total Peers: " + node.getAllPeers().size());
        System.out.println("Leader Peers: " + node.getLeaderPeers().size());
        System.out.println("Key Node Peers: " + node.getKeyNodePeers().size());
        System.out.println("Messages: " + node.getAllMessages().size());
        System.out.println();
    }
    
    private static void showRooms() {
        if (node == null) return;
        
        var rooms = node.getDiscoveredRooms();
        
        System.out.println("\n=== Available Chat Rooms (" + rooms.size() + ") ===");
        System.out.println("Discovery: Broadcasting on UDP ports 6000-6099");
        System.out.println("Note: Make sure UDP broadcast is allowed by your firewall\n");
        
        if (rooms.isEmpty()) {
            System.out.println("No rooms discovered on local network.");
            System.out.println("\nYou can:");
            System.out.println("  1. Wait for rooms to appear (quick initial discovery, then every 5 seconds)");
            System.out.println("  2. Start your own room as seed node");
            System.out.println("  3. Manually connect using IP:port if room is on different network");
            System.out.println("\nTroubleshooting:");
            System.out.println("  - Ensure other nodes are running and on the same network");
            System.out.println("  - Check firewall allows UDP broadcast on ports 6000-6099");
            System.out.println("  - On some networks, broadcast may be blocked - use manual connection");
        } else {
            int index = 1;
            for (RoomInfo room : rooms) {
                System.out.println("  " + index + ". " + room);
                index++;
            }
            System.out.println("\nTo join a room, use: /join <number>");
            System.out.println("Example: /join 1");
        }
        System.out.println();
    }

    private static void clearConsole() {
        for (int i = 0; i < 50; i++) {
            System.out.println();
        }
        System.out.println("-- Screen cleared (local view only) --");
    }
    
    private static void disconnect() {
        if (node == null || !node.isRunning()) {
            System.out.println("Already disconnected.");
            return;
        }
        
        System.out.print("Are you sure you want to disconnect? [Y]/n: ");
        String confirm = scanner.nextLine().trim();
        
        if (isYes(confirm)) {
            stopRobot(false);
            node.stop();
            System.out.println("Disconnected from network.");
            System.out.println("Use /reconnect to join another network or /quit to exit.");
        } else {
            System.out.println("Disconnect cancelled.");
        }
    }

    private static void createNewRoom() {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }

        System.out.print("Enter name for your new room: ");
        String roomName = scanner.nextLine().trim();

        if (roomName.isEmpty()) {
            System.out.println("Room creation cancelled.");
            return;
        }

        // If connected, disconnect first
        if (node.isRunning() && !node.getAllPeers().isEmpty()) {
            stopRobot(false);
            node.prepareForJoin(); // This disconnects and clears state
        }

        // Restart node if stopped
        if (!node.isRunning()) {
            try {
                node.start();
            } catch (Exception e) {
                System.err.println("Failed to start node: " + e.getMessage());
                return;
            }
        }

        // Set room name and become seed/key node
        node.setRoomName(roomName);
        node.ensureSeedKey();

        System.out.println("\nCreated new room: " + roomName);
        System.out.println("You are the seed node. Others can join at: " + advertisedIp + ":" + node.getPort());
    }
    
    private static void reconnect() {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        
        if (node.isRunning()) {
            System.out.println("Already connected. Use /disconnect first if you want to switch networks.");
            return;
        }
        
        System.out.println("\n=== Reconnecting ===");
        
        try {
            node.start();
            connectToNetwork();
            if (robotEnabled && (robotThread == null || !robotThread.isAlive())) {
                toggleRobot();
            }
        } catch (Exception e) {
            System.err.println("Failed to reconnect: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("Socket closed")) {
                System.err.println("Please restart the application to reconnect.");
            }
        }
    }
    
    private static void joinRoomByNumber(String numberStr) {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        
        try {
            int roomNumber = Integer.parseInt(numberStr.trim());
            var rooms = new ArrayList<>(node.getDiscoveredRooms());
            
            if (roomNumber < 1 || roomNumber > rooms.size()) {
                System.out.println("Invalid room number. Use /rooms to see available rooms.");
                return;
            }
            
            RoomInfo room = rooms.get(roomNumber - 1);
            
            // Check if already in this room (by roomId or by address:port)
            boolean sameRoom = room.getRoomId().equals(node.getRoomId());
            boolean sameAddress = room.getSeedNodeAddress().equals(node.getIpAddress()) 
                                  && room.getSeedNodePort() == node.getPort();
            
            if (sameRoom || sameAddress) {
                System.out.println("You are already in this room!");
                return;
            }
            
            System.out.println("Joining room: " + room.getRoomName());
            
            // Disconnect from current network if connected
            if (node.isRunning() && !node.getAllPeers().isEmpty()) {
                System.out.print("Disconnect from current room first? [Y]/n: ");
                String confirm = scanner.nextLine().trim();
                if (isYes(confirm)) {
                    node.stop();
                    Thread.sleep(500);
                    node.start();
                } else {
                    System.out.println("Join cancelled.");
                    return;
                }
            }
            
            // Join the selected room
            node.prepareForJoin();
            node.joinNetwork(room.getSeedNodeAddress(), room.getSeedNodePort());
            Thread.sleep(1000);
            
            System.out.println("Joined room: " + room.getRoomName());
            
        } catch (NumberFormatException e) {
            System.out.println("Invalid room number. Please enter a number.");
        } catch (Exception e) {
            System.err.println("Failed to join room: " + e.getMessage());
        }
    }

    private static void renameRoom(String newName) {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        if (newName.isEmpty()) {
            System.out.println("Room name cannot be empty.");
            return;
        }
        node.setRoomName(newName);
        node.triggerDiscoveryBroadcast();
        System.out.println("Room renamed to: " + newName);
    }

    private static void rebuildHistory() {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        if (!node.isRunning()) {
            System.out.println("Not connected. Use /reconnect before resyncing history.");
            return;
        }
        boolean success = node.rebuildChatHistory();
        if (success) {
            System.out.println("Requested chat history from peers. Messages will repopulate shortly.");
        } else {
            System.out.println("No peers available to rebuild history right now.");
        }
    }

    private static void reloadHistoryFromLog() {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        boolean success = node.loadHistoryFromLocalLog();
        if (success) {
            System.out.println("Reloaded chat history from local log.");
            showMessages();
        } else {
            System.out.println("Failed to reload local history (log missing or empty).");
        }
    }

    private static void configureNetworkLoss(String percentStr) {
        try {
            double percent = Double.parseDouble(percentStr);
            if (percent < 0 || percent > 100) {
                System.out.println("Value must be between 0 and 100.");
                return;
            }
            NetworkConditions.setDropPercent(percent);
            if (percent == 0) {
                System.out.println("Network loss simulation disabled.");
            } else {
                System.out.println("Simulating ~" + percent + "% packet loss on inbound and outbound traffic.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid number. Usage: /netloss <0-100>");
        }
    }

    private static void showNetworkLoss() {
        double outbound = NetworkConditions.getOutboundDropPercent();
        double inbound = NetworkConditions.getInboundDropPercent();
        if (outbound == 0 && inbound == 0) {
            System.out.println("Network loss simulation is currently disabled.");
        } else {
            System.out.println(String.format("Current simulated loss: outbound %.1f%%, inbound %.1f%%", outbound, inbound));
        }
    }

    private static void addDiscoverySeed(String args) {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        String[] tokens = args.split("\\s+");
        if (tokens.length != 2) {
            System.out.println("Usage: /seed <ip> <port>");
            return;
        }
        String ip = tokens[0];
        if (!isValidIP(ip)) {
            System.out.println("Invalid IP address.");
            return;
        }
        try {
            int port = Integer.parseInt(tokens[1]);
            if (port < 1024 || port > 65535) {
                System.out.println("Port must be between 1024 and 65535.");
                return;
            }
            node.addDiscoverySeed(ip, port);
            System.out.println("Added remote discovery seed " + ip + ":" + port + ".");
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number. Usage: /seed <ip> <port>");
        }
    }

    private static void showDiscoverySeeds() {
        if (node == null) {
            System.out.println("Error: Node not initialized.");
            return;
        }
        Collection<InetSocketAddress> seeds = node.getDiscoverySeeds();
        if (seeds.isEmpty()) {
            System.out.println("No remote discovery seeds configured.");
            return;
        }
        System.out.println("Configured seeds:");
        for (InetSocketAddress seed : seeds) {
            System.out.println("  - " + seed.getAddress().getHostAddress() + ":" + seed.getPort());
        }
    }

    private static void sendFile(String rawPath) {
        if (node == null || !node.isRunning()) {
            System.out.println("Join a room before sending files.");
            return;
        }
        if (rawPath == null || rawPath.isBlank()) {
            System.out.println("Usage: /sendfile <path>");
            return;
        }
        try {
            Path path = Paths.get(rawPath.replace("\"", ""));
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                System.out.println("File not found: " + path);
                return;
            }
            long size = Files.size(path);
            if (size == 0) {
                System.out.println("File is empty.");
                return;
            }
            if (size > MAX_FILE_BYTES) {
                System.out.println("File too large for demo transfer (max " + (MAX_FILE_BYTES / 1024) + " KB).");
                return;
            }
            byte[] data = Files.readAllBytes(path);
            boolean sent = node.sendFile(path.getFileName().toString(), data);
            if (sent) {
                System.out.println("Sent file '" + path.getFileName() + "' (" + size + " bytes) to room.");
            }
        } catch (Exception e) {
            System.err.println("Failed to send file: " + e.getMessage());
        }
    }

    private static void toggleRobot() {
        if (robotEnabled) {
            stopRobot(false);
            return;
        }
        if (node == null || !node.isRunning()) {
            System.out.println("Robot requires an active connection. Join or reconnect first.");
            return;
        }
        robotEnabled = true;
        robotThread = new Thread(SimpleChatClient::runRobotLoop, "RobotChatThread");
        robotThread.setDaemon(true);
        robotThread.start();
        System.out.println("Robot chat enabled. Type /robot again to stop.");
    }

    private static void runRobotLoop() {
        Random random = new Random();
        while (robotEnabled && running) {
            try {
                if (node == null || !node.isRunning()) {
                    stopRobot(true);
                    System.out.println("Robot paused because the node is disconnected.");
                    break;
                }
                String base = ROBOT_MESSAGES[random.nextInt(ROBOT_MESSAGES.length)];
                String message = "[Robot] " + base;
                if (random.nextBoolean()) {
                    message += " (#" + (100 + random.nextInt(900)) + ")";
                }
                // Robot messages - don't track pending status
                node.sendChatMessage(message);
                long delayWindow = ROBOT_MAX_DELAY_MS - ROBOT_MIN_DELAY_MS;
                long delay = ROBOT_MIN_DELAY_MS + (delayWindow <= 0 ? 0 : random.nextInt((int) delayWindow));
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Robot chat error: " + e.getMessage());
            }
        }
    }

    private static void stopRobot(boolean silent) {
        if (!robotEnabled) {
            return;
        }
        robotEnabled = false;
        if (robotThread != null) {
            robotThread.interrupt();
            robotThread = null;
        }
        if (!silent) {
            System.out.println("Robot chat disabled.");
        }
    }
}
