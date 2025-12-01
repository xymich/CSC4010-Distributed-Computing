import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import javax.swing.text.*;

public class ChatGUI extends JFrame implements MessageStatusListener {
    private static final Color SKYPE_BLUE = new Color(0, 175, 240);
    private static final Color SKYPE_DARK_BLUE = new Color(0, 120, 212);
    private static final Color SIDEBAR_BG = new Color(45, 45, 45);
    private static final Color CHAT_BG = new Color(26, 26, 26);
    private static final Color INPUT_BG = new Color(51, 51, 51);
    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Color TEXT_MUTED = new Color(170, 170, 170);
    private static final Color TEXT_PENDING = new Color(128, 128, 128); 
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    // ===== STATE =====
    private ChatNode node;
    private volatile boolean robotEnabled = false;
    private Thread robotThread;
    private static final String[] ROBOT_MESSAGES = {
        "Hello.", "Robot status: still online.", "Anyone else love Lamport clocks?",
        "Latency feels nice from here.", "Robot message: 00111000010100101001"
    };
    
    // ===== PENDING MESSAGE TRACKING =====
    // Maps messageId -> message text (for finding and updating color)
    private Map<UUID, String> pendingMessageTexts = new ConcurrentHashMap<>();
    
    // ===== BUFFERED OUTPUT =====
    // Buffer messages before GUI is ready
    private java.util.List<String> bufferedMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile boolean guiReady = false;

    // ===== UI COMPONENTS =====
    private JTextPane chatPane;
    private JTextField inputField;
    private DefaultListModel<String> peerListModel;
    private DefaultListModel<String> roomListModel;
    private JList<String> peerList;
    private JList<String> roomList;
    private JLabel statusLabel;
    private JLabel roomNameLabel;
    private JButton disconnectBtn;  // Toggles between Disconnect/Reconnect
    
    // Store original System.out for restoration
    private PrintStream originalOut;

    // ===== SETUP =====
    private String nickname;
    private String advertisedIp;
    private int port;

    public ChatGUI() {
        // Don't show main window yet - show setup first
        // Capture original System.out
        originalOut = System.out;
    }

    public void initialize() {
        showSetupDialog();
    }

    private void showSetupDialog() {
        JDialog setupDialog = new JDialog(this, "P2P Chat - Setup", true);
        setupDialog.setSize(380, 480);
        setupDialog.setLocationRelativeTo(null);
        setupDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(SIDEBAR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        // Logo
        JLabel logoLabel = createLogoLabel(80);
        logoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Title
        JLabel titleLabel = new JLabel("P2P Distributed Chat");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(SKYPE_BLUE);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Nickname
        JLabel nickLabel = createLabel("Nickname:");
        nickLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JTextField nickField = createTextField("");
        nickField.setAlignmentX(Component.CENTER_ALIGNMENT);
        nickField.setMaximumSize(new Dimension(300, 35));

        // IP
        String detectedIp = NetworkUtils.detectBestAddress();
        JLabel ipLabel = createLabel("Advertise IP:");
        ipLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JTextField ipField = createTextField(detectedIp);
        ipField.setAlignmentX(Component.CENTER_ALIGNMENT);
        ipField.setMaximumSize(new Dimension(300, 35));

        // Port
        JLabel portLabel = createLabel("Port (1024-65535):");
        portLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JTextField portField = createTextField("8080");
        portField.setAlignmentX(Component.CENTER_ALIGNMENT);
        portField.setMaximumSize(new Dimension(300, 35));

        // Error label
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(Color.RED);
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Start button
        JButton startBtn = createButton("Start Chat", true);
        startBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        startBtn.setMaximumSize(new Dimension(200, 40));

        startBtn.addActionListener(e -> {
            String nick = nickField.getText().trim();
            String ip = ipField.getText().trim();
            String portStr = portField.getText().trim();

            if (nick.isEmpty() || nick.contains(" ") || nick.startsWith("/")) {
                errorLabel.setText("Invalid nickname (no spaces, no leading /)");
                return;
            }

            int p;
            try {
                p = Integer.parseInt(portStr);
                if (p < 1024 || p > 65535) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid port (1024-65535)");
                return;
            }

            this.nickname = nick;
            this.advertisedIp = ip;
            this.port = p;

            try {
                // Redirect System.out BEFORE creating the node so we capture all messages
                redirectSystemOut();
                
                node = new ChatNode(nickname, advertisedIp, port);
                node.addMessageStatusListener(this);  // Register for delivery status updates
                node.start();
                setupDialog.dispose();
                showMainWindow();
            } catch (Exception ex) {
                errorLabel.setText("Failed: " + ex.getMessage());
            }
        });

        // Add components with spacing
        panel.add(Box.createVerticalStrut(10));
        panel.add(logoLabel);
        panel.add(Box.createVerticalStrut(15));
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(25));
        panel.add(nickLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(nickField);
        panel.add(Box.createVerticalStrut(15));
        panel.add(ipLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(ipField);
        panel.add(Box.createVerticalStrut(15));
        panel.add(portLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(portField);
        panel.add(Box.createVerticalStrut(20));
        panel.add(startBtn);
        panel.add(Box.createVerticalStrut(10));
        panel.add(errorLabel);

        setupDialog.add(panel);
        setupDialog.setVisible(true);
    }

    private void showMainWindow() {
        setTitle("P2P Chat - " + nickname);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 650);
        setLocationRelativeTo(null);

        // Main layout
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(CHAT_BG);

        // Sidebar
        JPanel sidebar = createSidebar();
        mainPanel.add(sidebar, BorderLayout.WEST);

        // Chat area
        JPanel chatPanel = createChatPanel();
        mainPanel.add(chatPanel, BorderLayout.CENTER);

        add(mainPanel);

        // Flush buffered messages to GUI and mark as ready
        flushBufferedMessages();

        setVisible(true);

        // Ask to join network
        SwingUtilities.invokeLater(this::askToJoinNetwork);
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(SIDEBAR_BG);
        sidebar.setPreferredSize(new Dimension(220, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Logo - wrap in panel for proper centering
        JLabel logo = createLogoLabel(48);
        JPanel logoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        logoPanel.setBackground(SIDEBAR_BG);
        logoPanel.add(logo);
        logoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        // Status - wrap in panel for proper centering
        statusLabel = new JLabel("Status: Connected");
        statusLabel.setForeground(TEXT_COLOR);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        statusPanel.setBackground(SIDEBAR_BG);
        statusPanel.add(statusLabel);
        statusPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        roomNameLabel = new JLabel("Room: " + node.getRoomName());
        roomNameLabel.setForeground(SKYPE_BLUE);
        roomNameLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        JPanel roomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        roomPanel.setBackground(SIDEBAR_BG);
        roomPanel.add(roomNameLabel);
        roomPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        // Peers section
        JLabel peersLabel = createLabel("Online Peers");
        peersLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        JPanel peersLabelPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        peersLabelPanel.setBackground(SIDEBAR_BG);
        peersLabelPanel.add(peersLabel);
        peersLabelPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        peerListModel = new DefaultListModel<>();
        peerList = new JList<>(peerListModel);
        stylePeerList(peerList);
        JScrollPane peerScroll = new JScrollPane(peerList);
        peerScroll.setPreferredSize(new Dimension(200, 120));
        peerScroll.setBorder(BorderFactory.createEmptyBorder());
        refreshPeerList();

        // Rooms section
        JLabel roomsLabel = createLabel("Discovered Rooms");
        roomsLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        JPanel roomsLabelPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        roomsLabelPanel.setBackground(SIDEBAR_BG);
        roomsLabelPanel.add(roomsLabel);
        roomsLabelPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        stylePeerList(roomList);
        JScrollPane roomScroll = new JScrollPane(roomList);
        roomScroll.setPreferredSize(new Dimension(200, 100));
        roomScroll.setBorder(BorderFactory.createEmptyBorder());
        refreshRoomList();

        // Buttons
        JButton joinBtn = createButton("Join Room", true);
        joinBtn.setMaximumSize(new Dimension(200, 30));
        joinBtn.addActionListener(e -> joinSelectedRoom());

        JButton newRoomBtn = createButton("New Room", true);
        newRoomBtn.setMaximumSize(new Dimension(200, 30));
        newRoomBtn.addActionListener(e -> createNewRoom());

        JButton refreshBtn = createButton("Refresh", false);
        refreshBtn.setMaximumSize(new Dimension(200, 30));
        refreshBtn.addActionListener(e -> {
            refreshPeerList();
            refreshRoomList();
        });

        JButton resyncBtn = createButton("Resync History", false);
        resyncBtn.setMaximumSize(new Dimension(200, 30));
        resyncBtn.addActionListener(e -> resyncHistory());

        JButton resyncLocalBtn = createButton("Load Local Log", false);
        resyncLocalBtn.setMaximumSize(new Dimension(200, 30));
        resyncLocalBtn.addActionListener(e -> loadLocalHistory());

        JButton sendFileBtn = createButton("Send File", false);
        sendFileBtn.setMaximumSize(new Dimension(200, 30));
        sendFileBtn.addActionListener(e -> sendFile());

        JButton robotBtn = createButton("Toggle Robot", false);
        robotBtn.setMaximumSize(new Dimension(200, 30));
        robotBtn.addActionListener(e -> toggleRobot());

        disconnectBtn = createButton("Disconnect", false);
        disconnectBtn.setMaximumSize(new Dimension(200, 30));
        disconnectBtn.addActionListener(e -> toggleConnection());

        JButton logoutBtn = createButton("Logout", false);
        logoutBtn.setMaximumSize(new Dimension(200, 30));
        logoutBtn.addActionListener(e -> logout());

        // Layout
        sidebar.add(logoPanel);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(statusPanel);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(roomPanel);
        sidebar.add(Box.createVerticalStrut(15));
        sidebar.add(new JSeparator());
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(peersLabelPanel);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(peerScroll);
        sidebar.add(Box.createVerticalStrut(15));
        sidebar.add(roomsLabelPanel);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(roomScroll);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(joinBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(newRoomBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(refreshBtn);
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(resyncBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(resyncLocalBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(sendFileBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(robotBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(disconnectBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(logoutBtn);

        return sidebar;
    }

    private JPanel createChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(CHAT_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Chat display
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setBackground(CHAT_BG);
        chatPane.setForeground(TEXT_COLOR);
        chatPane.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        chatPane.setCaretColor(TEXT_COLOR);

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(BorderFactory.createLineBorder(INPUT_BG));
        chatScroll.getViewport().setBackground(CHAT_BG);

        // Input area
        JPanel inputPanel = new JPanel(new BorderLayout(10, 0));
        inputPanel.setBackground(CHAT_BG);

        inputField = new JTextField();
        inputField.setBackground(INPUT_BG);
        inputField.setForeground(TEXT_COLOR);
        inputField.setCaretColor(TEXT_COLOR);
        inputField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        inputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INPUT_BG),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        inputField.addActionListener(e -> sendMessage());

        JButton sendBtn = createButton("Send", true);
        sendBtn.setPreferredSize(new Dimension(80, 35));
        sendBtn.addActionListener(e -> sendMessage());

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);

        panel.add(chatScroll, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ===== ACTIONS =====

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        inputField.setText("");

        if (text.startsWith("/")) {
            handleCommand(text);
        } else {
            if (node != null && node.isRunning()) {
                UUID messageId = node.sendChatMessage(text);
                if (messageId != null) {
                    // Display own message - grey if pending, white if immediately delivered
                    String msgText = "[" + node.getClock() + "] " + nickname + ": " + text;
                    if (node.isMessagePending(messageId)) {
                        appendChatPending(msgText, messageId);
                    } else {
                        appendChat(msgText, TEXT_COLOR);
                    }
                }
            } else {
                appendChat("[System] Not connected to network.", TEXT_MUTED);
            }
        }
    }
    
    /**
     * Append a pending message (grey) and track its text for later update
     */
    private void appendChatPending(String text, UUID messageId) {
        // Store the text for later lookup (without newline)
        pendingMessageTexts.put(messageId, text);
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, TEXT_PENDING);
            try {
                doc.insertString(doc.getLength(), text + "\n", attrs);
                chatPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // Ignore
            }
        });
    }
    
    /**
     * MessageStatusListener implementation - update message color when delivered
     */
    @Override
    public void onMessageStatusChanged(UUID messageId, boolean delivered) {
        if (delivered) {
            String messageText = pendingMessageTexts.remove(messageId);
            if (messageText != null) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        StyledDocument doc = chatPane.getStyledDocument();
                        String fullText = doc.getText(0, doc.getLength());
                        int idx = fullText.indexOf(messageText);
                        if (idx >= 0) {
                            SimpleAttributeSet attrs = new SimpleAttributeSet();
                            StyleConstants.setForeground(attrs, TEXT_COLOR);
                            doc.setCharacterAttributes(idx, messageText.length(), attrs, false);
                        }
                    } catch (BadLocationException e) {
                        // Ignore
                    }
                });
            }
        }
    }

    private void handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/peers" -> {
                refreshPeerList();
                appendChat("[System] Peer list refreshed.", TEXT_MUTED);
            }
            case "/messages" -> {
                appendChat("=== Message History ===", SKYPE_BLUE);
                for (var msg : node.getAllMessages()) {
                    appendChat(msg.toString(), TEXT_COLOR);
                }
            }
            case "/status" -> appendChat("[Status] " + node.toString() + " | Clock: " + node.getClock(), TEXT_MUTED);
            case "/rooms" -> {
                refreshRoomList();
                appendChat("[System] Room list refreshed.", TEXT_MUTED);
            }
            case "/join" -> {
                if (args.isEmpty()) {
                    appendChat("Usage: /join <room_number> or /join <ip:port>", TEXT_MUTED);
                } else {
                    joinByArgument(args);
                }
            }
            case "/roomname" -> {
                if (args.isEmpty()) {
                    appendChat("Usage: /roomname <new name>", TEXT_MUTED);
                } else {
                    renameRoom(args);
                }
            }
            case "/resync" -> resyncHistory();
            case "/newroom" -> createNewRoom();
            case "/clear" -> chatPane.setText("");
            case "/robot" -> toggleRobot();
            case "/disconnect" -> disconnect();
            case "/reconnect" -> reconnect();
            case "/logout" -> logout();
            case "/seed" -> {
                if (args.isEmpty()) {
                    appendChat("Usage: /seed <ip> <port>", TEXT_MUTED);
                } else {
                    addDiscoverySeed(args);
                }
            }
            case "/seeds" -> showDiscoverySeeds();
            case "/netloss" -> {
                if (args.isEmpty()) {
                    showNetworkLoss();
                } else {
                    configureNetworkLoss(args);
                }
            }
            case "/sendfile" -> sendFile();
            case "/help" -> showHelp();
            case "/quit" -> {
                shutdown();
                System.exit(0);
            }
            default -> appendChat("[System] Unknown command: " + cmd + ". Type /help", TEXT_MUTED);
        }
    }

    private void showHelp() {
        appendChat("=== Commands ===", SKYPE_BLUE);
        appendChat("/peers - Refresh peer list", TEXT_MUTED);
        appendChat("/messages - Show message history", TEXT_MUTED);
        appendChat("/status - Show node status", TEXT_MUTED);
        appendChat("/rooms - Refresh room list", TEXT_MUTED);
        appendChat("/join <number|ip:port> - Join a room by number or address", TEXT_MUTED);
        appendChat("/roomname <name> - Rename your room (key node only)", TEXT_MUTED);
        appendChat("/resync - Rebuild chat history", TEXT_MUTED);
        appendChat("/newroom - Create a new room as seed node", TEXT_MUTED);
        appendChat("/clear - Clear chat display", TEXT_MUTED);
        appendChat("/robot - Toggle robot chat", TEXT_MUTED);
        appendChat("/seed <ip> <port> - Add a remote discovery seed", TEXT_MUTED);
        appendChat("/seeds - Show configured discovery seeds", TEXT_MUTED);
        appendChat("/netloss [0-100] - Simulate packet loss percentage", TEXT_MUTED);
        appendChat("/sendfile - Send a file to the room", TEXT_MUTED);
        appendChat("/disconnect - Disconnect from network", TEXT_MUTED);
        appendChat("/reconnect - Reconnect to network", TEXT_MUTED);
        appendChat("/logout - Logout and change identity", TEXT_MUTED);
        appendChat("/help - Show this help", TEXT_MUTED);
        appendChat("/quit - Exit application", TEXT_MUTED);
    }

    private void joinByArgument(String arg) {
        if (node == null) {
            appendChat("[Error] Node not initialized.", Color.RED);
            return;
        }

        // Check if it's an IP:port format
        if (arg.contains(":") || arg.contains(".")) {
            String[] addrParts = arg.split(":");
            if (addrParts.length == 2) {
                try {
                    String ip = addrParts[0].trim();
                    int p = Integer.parseInt(addrParts[1].trim());
                    node.prepareForJoin();
                    node.joinNetwork(ip, p);
                    appendChat("[System] Joining " + ip + ":" + p + "...", TEXT_MUTED);
                    scheduleRefresh();
                    return;
                } catch (NumberFormatException e) {
                    appendChat("[Error] Invalid port number.", Color.RED);
                    return;
                }
            }
        }

        // Try as room number
        try {
            int roomNum = Integer.parseInt(arg.trim());
            var rooms = new ArrayList<>(node.getDiscoveredRooms());
            if (roomNum < 1 || roomNum > rooms.size()) {
                appendChat("[Error] Invalid room number. Use /rooms to see list.", Color.RED);
                return;
            }
            RoomInfo room = rooms.get(roomNum - 1);
            
            // Check if already in this room
            if (room.getRoomId().equals(node.getRoomId())) {
                appendChat("[System] Already in this room.", Color.ORANGE);
                return;
            }
            
            node.prepareForJoin();
            node.joinNetwork(room.getSeedNodeAddress(), room.getSeedNodePort());
            appendChat("[System] Joining room: " + room.getRoomName(), SKYPE_BLUE);
            scheduleRefresh();
        } catch (NumberFormatException e) {
            appendChat("[Error] Invalid room number or address format.", Color.RED);
        }
    }

    private void renameRoom(String newName) {
        if (node == null || !node.isRunning()) {
            appendChat("[Error] Not connected.", Color.RED);
            return;
        }
        node.setRoomName(newName);
        node.triggerDiscoveryBroadcast();
        roomNameLabel.setText("Room: " + newName);
        appendChat("[System] Room renamed to: " + newName, SKYPE_BLUE);
    }

    private void reconnect() {
        if (node == null) {
            appendChat("[Error] Node not initialized.", Color.RED);
            return;
        }
        if (node.isRunning()) {
            appendChat("[System] Already connected. Use /disconnect first.", TEXT_MUTED);
            return;
        }
        node.start();
        statusLabel.setText("Status: Connected");
        disconnectBtn.setText("Disconnect");
        appendChat("[System] Reconnected to network.", SKYPE_BLUE);
        refreshPeerList();
        refreshRoomList();
    }

    private void addDiscoverySeed(String args) {
        String[] tokens = args.split("\\s+");
        if (tokens.length != 2) {
            appendChat("Usage: /seed <ip> <port>", TEXT_MUTED);
            return;
        }
        try {
            String ip = tokens[0];
            int seedPort = Integer.parseInt(tokens[1]);
            if (seedPort < 1024 || seedPort > 65535) {
                appendChat("[Error] Port must be 1024-65535.", Color.RED);
                return;
            }
            node.addDiscoverySeed(ip, seedPort);
            appendChat("[System] Added discovery seed " + ip + ":" + seedPort, SKYPE_BLUE);
        } catch (NumberFormatException e) {
            appendChat("[Error] Invalid port number.", Color.RED);
        }
    }

    private void showDiscoverySeeds() {
        var seeds = node.getDiscoverySeeds();
        if (seeds.isEmpty()) {
            appendChat("[System] No remote discovery seeds configured.", TEXT_MUTED);
            return;
        }
        appendChat("=== Discovery Seeds ===", SKYPE_BLUE);
        for (var seed : seeds) {
            appendChat("  " + seed.getAddress().getHostAddress() + ":" + seed.getPort(), TEXT_MUTED);
        }
    }

    private void configureNetworkLoss(String percentStr) {
        try {
            double percent = Double.parseDouble(percentStr);
            if (percent < 0 || percent > 100) {
                appendChat("[Error] Value must be between 0 and 100.", Color.RED);
                return;
            }
            NetworkConditions.setDropPercent(percent);
            if (percent == 0) {
                appendChat("[System] Network loss simulation disabled.", TEXT_MUTED);
            } else {
                appendChat("[System] Simulating ~" + percent + "% packet loss.", SKYPE_BLUE);
            }
        } catch (NumberFormatException e) {
            appendChat("[Error] Invalid number. Usage: /netloss <0-100>", Color.RED);
        }
    }

    private void showNetworkLoss() {
        double outbound = NetworkConditions.getOutboundDropPercent();
        double inbound = NetworkConditions.getInboundDropPercent();
        if (outbound == 0 && inbound == 0) {
            appendChat("[System] Network loss simulation is disabled.", TEXT_MUTED);
        } else {
            appendChat(String.format("[System] Current loss: outbound %.1f%%, inbound %.1f%%", outbound, inbound), TEXT_MUTED);
        }
    }

    private void scheduleRefresh() {
        javax.swing.Timer timer = new javax.swing.Timer(1500, ev -> {
            refreshPeerList();
            refreshRoomList();
            if (node != null) {
                roomNameLabel.setText("Room: " + node.getRoomName());
            }
            ((javax.swing.Timer)ev.getSource()).stop();
        });
        timer.start();
    }

    private void askToJoinNetwork() {
        int choice = JOptionPane.showConfirmDialog(
            this,
            "Join an existing network?\n\nYes = Browse and join a room\nNo = Start as seed node",
            "Join Network",
            JOptionPane.YES_NO_OPTION
        );

        if (choice == JOptionPane.YES_OPTION) {
            node.prepareForJoin();
            refreshRoomList();
            appendChat("[System] Select a room and click 'Join Room', or enter IP:port manually.", TEXT_MUTED);
        } else {
            node.ensureSeedKey();
            appendChat("[System] Started as seed node. Others can join at: " + advertisedIp + ":" + port, SKYPE_BLUE);
        }
    }

    private void joinSelectedRoom() {
        String selected = roomList.getSelectedValue();
        if (selected == null || selected.isEmpty()) {
            // Manual IP:port input
            String input = JOptionPane.showInputDialog(this,
                "Enter peer address (IP:Port):",
                "Join Room",
                JOptionPane.PLAIN_MESSAGE);

            if (input != null && input.contains(":")) {
                String[] parts = input.split(":");
                try {
                    String ip = parts[0].trim();
                    int p = Integer.parseInt(parts[1].trim());
                    node.prepareForJoin();
                    node.joinNetwork(ip, p);
                    appendChat("[System] Joining " + ip + ":" + p + "...", TEXT_MUTED);
                    // Refresh after delay
                    javax.swing.Timer refreshTimer = new javax.swing.Timer(1500, ev -> {
                        refreshPeerList();
                        ((javax.swing.Timer)ev.getSource()).stop();
                    });
                    refreshTimer.start();
                } catch (Exception e) {
                    appendChat("[Error] Invalid address format.", Color.RED);
                }
            }
            return;
        }

        // Join selected room
        var rooms = new ArrayList<>(node.getDiscoveredRooms());
        int idx = roomList.getSelectedIndex();
        if (idx >= 0 && idx < rooms.size()) {
            RoomInfo room = rooms.get(idx);
            
            // Check if already in this room (by roomId or by address:port)
            boolean sameRoom = room.getRoomId().equals(node.getRoomId());
            boolean sameAddress = room.getSeedNodeAddress().equals(node.getIpAddress()) 
                                  && room.getSeedNodePort() == node.getPort();
            
            if (sameRoom || sameAddress) {
                appendChat("[System] You are already in this room!", Color.ORANGE);
                return;
            }
            
            node.prepareForJoin();
            node.joinNetwork(room.getSeedNodeAddress(), room.getSeedNodePort());
            appendChat("[System] Joining room: " + room.getRoomName(), SKYPE_BLUE);
            javax.swing.Timer roomTimer = new javax.swing.Timer(1500, ev -> {
                refreshPeerList();
                ((javax.swing.Timer)ev.getSource()).stop();
            });
            roomTimer.start();
        }
    }

    /**
     * Toggle between connected and disconnected states
     */
    private void toggleConnection() {
        if (node != null && node.isRunning()) {
            // Currently connected - disconnect
            disconnect();
        } else {
            // Currently disconnected - reconnect
            reconnect();
        }
    }

    private void disconnect() {
        if (node != null && node.isRunning()) {
            stopRobot();
            node.stop();
            appendChat("[System] Disconnected from network.", TEXT_MUTED);
            statusLabel.setText("Status: Disconnected");
            disconnectBtn.setText("Reconnect");
        }
    }

    private void createNewRoom() {
        if (node == null) {
            appendChat("[System] Node not initialized.", TEXT_MUTED);
            return;
        }

        // Ask for room name
        String roomName = JOptionPane.showInputDialog(this,
            "Enter name for your new room:",
            "Create New Room",
            JOptionPane.PLAIN_MESSAGE);

        if (roomName == null || roomName.trim().isEmpty()) {
            appendChat("[System] Room creation cancelled.", TEXT_MUTED);
            return;
        }

        // If connected, disconnect first
        if (node.isRunning() && !node.getAllPeers().isEmpty()) {
            stopRobot();
            node.prepareForJoin(); // This disconnects and clears state
        }

        // Set room name and become seed/key node
        node.setRoomName(roomName.trim());
        node.ensureSeedKey();

        // Update UI
        roomNameLabel.setText("Room: " + roomName.trim());
        statusLabel.setText("Status: Connected (Seed)");
        refreshPeerList();
        refreshRoomList();

        appendChat("[System] Created new room: " + roomName.trim(), SKYPE_BLUE);
        appendChat("[System] You are the seed node. Others can join at: " + advertisedIp + ":" + port, SKYPE_BLUE);
    }

    private void logout() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Logout and change your identity?\n\nThis will disconnect you and let you choose a new name, IP, and port.",
            "Logout",
            JOptionPane.YES_NO_OPTION);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        // Stop everything
        stopRobot();
        if (node != null) {
            node.removeMessageStatusListener(this);
            node.stop();
            node = null;
        }

        // Clear state
        pendingMessageTexts.clear();
        bufferedMessages.clear();
        guiReady = false;

        // Remove all components and reset frame
        getContentPane().removeAll();
        
        // Reset System.out to original
        System.setOut(originalOut);

        // Hide and fully dispose this window
        setVisible(false);
        dispose();

        // Create a completely new GUI instance
        SwingUtilities.invokeLater(() -> {
            ChatGUI newGui = new ChatGUI();
            newGui.initialize();
        });
    }
    private void resyncHistory() {
        if (node != null && node.isRunning()) {
            boolean success = node.rebuildChatHistory();
            if (success) {
                appendChat("[System] Requesting history resync...", TEXT_MUTED);
            } else {
                appendChat("[System] No peers available for resync.", TEXT_MUTED);
            }
        }
    }

    private void loadLocalHistory() {
        if (node == null) {
            appendChat("[System] Node not initialized.", TEXT_MUTED);
            return;
        }
        boolean success = node.loadHistoryFromLocalLog();
        if (success) {
            redrawChatFromHistory();
            appendChat("[System] Reloaded chat history from local log.", SKYPE_BLUE);
        } else {
            appendChat("[System] Local log unavailable or empty.", TEXT_MUTED);
        }
    }

    private void redrawChatFromHistory() {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            try {
                doc.remove(0, doc.getLength());
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, TEXT_COLOR);
                for (var msg : node.getAllMessages()) {
                    doc.insertString(doc.getLength(), msg.toString() + "\n", attrs);
                }
                chatPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // Ignore redraw errors
            }
        });
    }

    private void sendFile() {
        if (node == null || !node.isRunning()) {
            appendChat("[System] Connect to a room first.", TEXT_MUTED);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select File to Send");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                if (file.length() > MAX_FILE_BYTES) {
                    appendChat("[Error] File too large (max 2MB).", Color.RED);
                    return;
                }
                byte[] data = Files.readAllBytes(file.toPath());
                boolean sent = node.sendFile(file.getName(), data);
                if (sent) {
                    appendChat("[System] Sent file: " + file.getName(), SKYPE_BLUE);
                }
            } catch (Exception e) {
                appendChat("[Error] Failed to send file: " + e.getMessage(), Color.RED);
            }
        }
    }

    private void toggleRobot() {
        if (robotEnabled) {
            stopRobot();
            appendChat("[System] Robot chat disabled.", TEXT_MUTED);
        } else {
            if (node == null || !node.isRunning()) {
                appendChat("[System] Connect first to enable robot.", TEXT_MUTED);
                return;
            }
            robotEnabled = true;
            robotThread = new Thread(this::runRobotLoop, "GUIRobotThread");
            robotThread.setDaemon(true);
            robotThread.start();
            appendChat("[System] Robot chat enabled.", SKYPE_BLUE);
        }
    }

    private void runRobotLoop() {
        Random random = new Random();
        while (robotEnabled) {
            try {
                if (node == null || !node.isRunning()) {
                    stopRobot();
                    break;
                }
                String msg = "[Robot] " + ROBOT_MESSAGES[random.nextInt(ROBOT_MESSAGES.length)];
                node.sendChatMessage(msg);
                Thread.sleep(2000 + random.nextInt(4000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void stopRobot() {
        robotEnabled = false;
        if (robotThread != null) {
            robotThread.interrupt();
            robotThread = null;
        }
    }

    // ===== HELPERS =====

    private void refreshPeerList() {
        if (node == null) return;
        SwingUtilities.invokeLater(() -> {
            peerListModel.clear();
            for (var peer : node.getAllPeers()) {
                String display = peer.getNickname();
                if (peer.isLeaderKey()) display += " [KEY]";
                peerListModel.addElement(display);
            }
        });
    }

    private void refreshRoomList() {
        if (node == null) return;
        SwingUtilities.invokeLater(() -> {
            roomListModel.clear();
            for (var room : node.getDiscoveredRooms()) {
                roomListModel.addElement(room.getRoomName() + " (" + room.getMemberCount() + ")");
            }
        });
    }

    private void appendChat(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, color);
            try {
                doc.insertString(doc.getLength(), text + "\n", attrs);
                chatPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // Ignore
            }
        });
    }

    private void redirectSystemOut() {
        PrintStream guiOut = new PrintStream(originalOut) {
            @Override
            public void println(String x) {
                super.println(x);
                if (guiReady && chatPane != null) {
                    appendChat(x, TEXT_COLOR);
                } else {
                    // Buffer messages until GUI is ready
                    bufferedMessages.add(x);
                }
            }
            
            @Override
            public void println(Object x) {
                // ChatMessage and other objects go through here
                println(String.valueOf(x));
            }
        };
        System.setOut(guiOut);
    }
    
    private void flushBufferedMessages() {
        guiReady = true;
        synchronized (bufferedMessages) {
            for (String msg : bufferedMessages) {
                appendChat(msg, TEXT_COLOR);
            }
            bufferedMessages.clear();
        }
    }

    private void shutdown() {
        stopRobot();
        if (node != null) {
            node.stop();
        }
    }

    private JLabel createLogoLabel(int size) {
        JLabel label = new JLabel();
        label.setPreferredSize(new Dimension(size, size));
        label.setHorizontalAlignment(SwingConstants.CENTER);

        // Try to load logo.png
        try {
            Path logoPath = Paths.get("logo.png");
            if (Files.exists(logoPath)) {
                ImageIcon icon = new ImageIcon(logoPath.toString());
                Image scaled = icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
                label.setIcon(new ImageIcon(scaled));
            } else {
                // Fallback: show text placeholder
                label.setText("LOGO");
                label.setForeground(SKYPE_BLUE);
                label.setFont(new Font("Segoe UI", Font.BOLD, 12));
                label.setBorder(BorderFactory.createLineBorder(SKYPE_BLUE, 2));
            }
        } catch (Exception e) {
            label.setText("LOGO");
            label.setForeground(SKYPE_BLUE);
        }

        return label;
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT_MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JTextField createTextField(String initial) {
        JTextField field = new JTextField(initial);
        field.setBackground(INPUT_BG);
        field.setForeground(TEXT_COLOR);
        field.setCaretColor(TEXT_COLOR);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INPUT_BG),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        return field;
    }

    private JButton createButton(String text, boolean primary) {
        JButton btn = new JButton(text);
        btn.setBackground(primary ? SKYPE_BLUE : INPUT_BG);
        btn.setForeground(TEXT_COLOR);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);

        Color bg = primary ? SKYPE_BLUE : INPUT_BG;
        Color hover = primary ? SKYPE_DARK_BLUE : new Color(68, 68, 68);

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setBackground(hover);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                btn.setBackground(bg);
            }
        });

        return btn;
    }

    private void stylePeerList(JList<String> list) {
        list.setBackground(INPUT_BG);
        list.setForeground(TEXT_COLOR);
        list.setSelectionBackground(SKYPE_DARK_BLUE);
        list.setSelectionForeground(TEXT_COLOR);
        list.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    }

    public static void main(String[] args) {
        // Use system look and feel for better integration
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // Dark theme overrides
        UIManager.put("Panel.background", CHAT_BG);
        UIManager.put("OptionPane.background", SIDEBAR_BG);
        UIManager.put("OptionPane.messageForeground", TEXT_COLOR);

        SwingUtilities.invokeLater(() -> {
            ChatGUI gui = new ChatGUI();
            gui.initialize();
        });
    }
}
