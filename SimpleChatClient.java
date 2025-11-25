import java.net.InetAddress;
import java.util.Scanner;

public class SimpleChatClient {
    
    private static ChatNode node;
    private static Scanner scanner;
    private static boolean running = true;
    
    public static void main(String[] args) {
        scanner = new Scanner(System.in);
        
        System.out.println("=== P2P Distributed Chat System ===\n");
        
        // Get user input with validation
        String nickname = getNickname();
        int port = getPort();
        
        // Create node with error handling
        try {
            node = new ChatNode(nickname, "127.0.0.1", port);
            node.start();
        } catch (Exception e) {
            System.err.println("Failed to start node: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
                System.err.println("Port " + port + " is already in use. Please choose a different port.");
            }
            scanner.close();
            return;
        }
        
        System.out.println("\nYour node info:");
        System.out.println(node);
        
        // Check if joining existing network
        System.out.print("\nJoin existing network? [Y]/n: ");
        String joinChoice = scanner.nextLine().trim();
        
        if (isYes(joinChoice)) {
            connectToNetwork();
        } else {
            System.out.println("\nStarted as seed node. Others can join at: 127.0.0.1:" + port);
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
        while (true) {
            System.out.println("\nConnecting to network...");
            System.out.print("Enter peer IP address (or 'n' to cancel): ");
            String peerIp = scanner.nextLine().trim();
            
            // Check for cancel
            if (peerIp.equalsIgnoreCase("n")) {
                System.out.println("Connection cancelled. Started as seed node.");
                return;
            }
            
            // Check for command
            if (peerIp.startsWith("/")) {
                handleCommand(peerIp);
                continue;
            }
            
            // Validate IP address
            if (!isValidIP(peerIp)) {
                System.out.println("Invalid IP address! Please enter a valid IPv4 address (e.g., 127.0.0.1)");
                System.out.print("Try again? [Y]/n: ");
                String retry = scanner.nextLine().trim();
                if (!isYes(retry)) {
                    System.out.println("Started as seed node.");
                    return;
                }
                continue;
            }
            
            System.out.print("Enter peer port (or 'n' to cancel): ");
            String portInput = scanner.nextLine().trim();
            
            // Check for cancel
            if (portInput.equalsIgnoreCase("n")) {
                System.out.println("Connection cancelled. Started as seed node.");
                return;
            }
            
            // Check for command
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
                
                System.out.println("Attempting to join " + peerIp + ":" + peerPort + "...");
                node.joinNetwork(peerIp, peerPort);
                
                // Give time for join to complete
                Thread.sleep(1000);
                return;
                
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number!");
                System.out.print("Try again? [Y]/n: ");
                String retry = scanner.nextLine().trim();
                if (!isYes(retry)) {
                    System.out.println("Started as seed node.");
                    return;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
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
        System.out.println("/rooms          - List available chat rooms (TODO)");
        System.out.println("/disconnect     - Disconnect from current network");
        System.out.println("/reconnect      - Reconnect to a network");
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
        System.out.println("\n=== Available Chat Rooms ===");
        System.out.println("Room discovery not yet implemented.");
        System.out.println("TODO: This will show all discoverable chat networks");
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
}
