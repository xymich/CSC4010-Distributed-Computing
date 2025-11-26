import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Scanner;

public class SimpleChatClient {
    
    private static ChatNode node;
    private static Scanner scanner;
    private static boolean running = true;
    private static String advertisedIp;
    
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
        System.out.println("/disconnect     - Disconnect from current network");
        System.out.println("/reconnect      - Reconnect to a network");
        System.out.println("/roomname <name> - Rename your room");
        System.out.println("/help           - Show this help message");
        System.out.println("/quit           - Exit application");
        System.out.println("\nType a message and press Enter to send\n");
    }
    
    private static void chatLoop() {
        while (running) {
            try {
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
                        node.sendChatMessage(input);
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
                
            case "/disconnect":
                disconnect();
                break;
                
            case "/reconnect":
                reconnect();
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
        System.out.println("Swarm Peers: " + node.getSwarmPeers().size());
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
    
    private static void disconnect() {
        if (node == null || !node.isRunning()) {
            System.out.println("Already disconnected.");
            return;
        }
        
        System.out.print("Are you sure you want to disconnect? [Y]/n: ");
        String confirm = scanner.nextLine().trim();
        
        if (isYes(confirm)) {
            node.stop();
            System.out.println("Disconnected from network.");
            System.out.println("Use /reconnect to join another network or /quit to exit.");
        } else {
            System.out.println("Disconnect cancelled.");
        }
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
}
