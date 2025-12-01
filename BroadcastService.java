import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Simple HTTP service that broadcasts messages to the P2P chat network.
 * 
 * Usage:
 *   java BroadcastService
 * 
 * API Endpoints:
 *   GET  /message?text=Hello     - Broadcast a message
 *   POST /message                - Broadcast message from body (text/plain)
 *   GET  /status                 - Check service status
 *   GET  /peers                  - List connected peers
 * 
 * Example:
 *   curl "http://localhost:8080/message?text=Hello%20World"
 *   curl -X POST -d "Hello World" http://localhost:8080/message
 */
public class BroadcastService {
    
    private static ChatNode node;
    private static HttpServer server;
    private static int httpPort = 8080;
    
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== P2P Broadcast Service ===\n");
        
        // Get configuration
        System.out.print("Service nickname [BroadcastBot]: ");
        String nickname = scanner.nextLine().trim();
        if (nickname.isEmpty()) nickname = "BroadcastBot";
        
        String advertisedIp = NetworkUtils.detectBestAddress();
        System.out.println("Detected IP: " + advertisedIp);
        System.out.print("Advertise which IP [" + advertisedIp + "]: ");
        String ipInput = scanner.nextLine().trim();
        if (!ipInput.isEmpty()) advertisedIp = ipInput;
        
        System.out.print("Chat UDP port [5555]: ");
        String portInput = scanner.nextLine().trim();
        int chatPort = portInput.isEmpty() ? 5555 : Integer.parseInt(portInput);
        
        System.out.print("HTTP API port [8080]: ");
        String httpInput = scanner.nextLine().trim();
        httpPort = httpInput.isEmpty() ? 8080 : Integer.parseInt(httpInput);
        
        // Start chat node
        try {
            node = new ChatNode(nickname, advertisedIp, chatPort);
            node.start();
            System.out.println("\nChat node started on UDP port " + chatPort);
        } catch (Exception e) {
            System.err.println("Failed to start chat node: " + e.getMessage());
            return;
        }
        
        // Join network or start as seed
        System.out.print("\nJoin existing network? [Y]/n: ");
        String joinChoice = scanner.nextLine().trim();
        
        if (joinChoice.isEmpty() || joinChoice.toLowerCase().startsWith("y")) {
            System.out.print("Enter seed IP: ");
            String seedIp = scanner.nextLine().trim();
            System.out.print("Enter seed port: ");
            int seedPort = Integer.parseInt(scanner.nextLine().trim());
            
            node.prepareForJoin();
            try {
                node.joinNetwork(seedIp, seedPort);
                System.out.println("Joined network via " + seedIp + ":" + seedPort);
            } catch (Exception e) {
                System.err.println("Failed to join: " + e.getMessage());
            }
        } else {
            node.ensureSeedKey();
            System.out.println("Started as seed node");
        }
        
        // Start HTTP server
        try {
            server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            server.createContext("/message", new MessageHandler());
            server.createContext("/status", new StatusHandler());
            server.createContext("/peers", new PeersHandler());
            server.setExecutor(null);
            server.start();
            
            System.out.println("\n=== HTTP API Ready ===");
            System.out.println("Endpoints:");
            System.out.println("  GET  http://localhost:" + httpPort + "/message?text=YourMessage");
            System.out.println("  POST http://localhost:" + httpPort + "/message  (body = message)");
            System.out.println("  GET  http://localhost:" + httpPort + "/status");
            System.out.println("  GET  http://localhost:" + httpPort + "/peers");
            System.out.println("\nPress Enter to stop...");
            
        } catch (Exception e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
            node.stop();
            return;
        }
        
        // Wait for shutdown
        scanner.nextLine();
        
        System.out.println("Shutting down...");
        server.stop(0);
        node.stop();
        System.out.println("Done.");
    }
    
    /**
     * Handler for /message endpoint - broadcasts a chat message
     */
    static class MessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Add CORS headers
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            
            String method = exchange.getRequestMethod();
            String message = null;
            
            if ("GET".equals(method)) {
                // Parse query string
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);
                message = params.get("text");
                
            } else if ("POST".equals(method)) {
                // Read body
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                    message = body.toString().trim();
                }
                
            } else if ("OPTIONS".equals(method)) {
                // CORS preflight
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            
            String response;
            int statusCode;
            
            if (message == null || message.isEmpty()) {
                response = "{\"success\":false,\"error\":\"No message provided. Use ?text=YourMessage or POST body\"}";
                statusCode = 400;
            } else {
                try {
                    node.sendChatMessage(message);
                    int peerCount = node.getLeaderPeers().size();
                    response = "{\"success\":true,\"message\":\"" + escapeJson(message) + 
                               "\",\"peers\":" + peerCount + "}";
                    statusCode = 200;
                    System.out.println("[API] Broadcast: " + message + " (to " + peerCount + " peers)");
                } catch (Exception e) {
                    response = "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                    statusCode = 500;
                }
            }
            
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
    
    /**
     * Handler for /status endpoint
     */
    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            
            String response = "{" +
                "\"running\":" + node.isRunning() + "," +
                "\"nickname\":\"" + escapeJson(node.getNickname()) + "\"," +
                "\"nodeId\":\"" + node.getNodeId().toString() + "\"," +
                "\"room\":\"" + escapeJson(node.getRoomName()) + "\"," +
                "\"isKeyNode\":" + node.isLeaderKey() + "," +
                "\"peerCount\":" + node.getLeaderPeers().size() + "," +
                "\"lamportClock\":" + node.getClock() +
                "}";
            
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
    
    /**
     * Handler for /peers endpoint
     */
    static class PeersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            
            StringBuilder peers = new StringBuilder("[");
            boolean first = true;
            for (PeerInfo peer : node.getLeaderPeers()) {
                if (!first) peers.append(",");
                first = false;
                peers.append("{")
                     .append("\"nickname\":\"").append(escapeJson(peer.getNickname())).append("\",")
                     .append("\"nodeId\":\"").append(peer.getNodeId().toString()).append("\",")
                     .append("\"address\":\"").append(peer.getIpAddress()).append(":").append(peer.getPort()).append("\",")
                     .append("\"isKeyNode\":").append(peer.isLeaderKey())
                     .append("}");
            }
            peers.append("]");
            
            String response = "{\"peers\":" + peers.toString() + ",\"count\":" + node.getLeaderPeers().size() + "}";
            
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
    
    // Helper methods
    
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                try {
                    params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()));
                } catch (Exception e) {
                    params.put(pair[0], pair[1]);
                }
            }
        }
        return params;
    }
    
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
