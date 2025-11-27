import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.text.*;

/**
 * Swing-based GUI for P2P Chat with old Skype-inspired dark theme.
 * Place a 'logo.png' file in the application directory to display a custom logo.
 */
public class ChatGUI extends JFrame {

    // ===== THEME COLORS (Old Skype inspired) =====
    private static final Color SKYPE_BLUE = new Color(0, 175, 240);
    private static final Color SKYPE_DARK_BLUE = new Color(0, 120, 212);
    private static final Color SIDEBAR_BG = new Color(45, 45, 45);
    private static final Color CHAT_BG = new Color(26, 26, 26);
    private static final Color INPUT_BG = new Color(51, 51, 51);
    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Color TEXT_MUTED = new Color(170, 170, 170);
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    // ===== STATE =====
    private ChatNode node;
    private volatile boolean robotEnabled = false;
    private Thread robotThread;
    private static final String[] ROBOT_MESSAGES = {
        "Hello.", "Robot status: still online.", "Anyone else love Lamport clocks?",
        "Latency feels nice from here.", "Robot message: 00111000010100101001"
    };

    // ===== UI COMPONENTS =====
    private JTextPane chatPane;
    private JTextField inputField;
    private DefaultListModel<String> peerListModel;
    private DefaultListModel<String> roomListModel;
    private JList<String> peerList;
    private JList<String> roomList;
    private JLabel statusLabel;
    private JLabel roomNameLabel;

    // ===== SETUP =====
    private String nickname;
    private String advertisedIp;
    private int port;

    public ChatGUI() {
        // Don't show main window yet - show setup first
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
        JTextField nickField = createTextField("");
        nickField.setMaximumSize(new Dimension(300, 35));

        // IP
        String detectedIp = NetworkUtils.detectBestAddress();
        JLabel ipLabel = createLabel("Advertise IP:");
        JTextField ipField = createTextField(detectedIp);
        ipField.setMaximumSize(new Dimension(300, 35));

        // Port
        JLabel portLabel = createLabel("Port (1024-65535):");
        JTextField portField = createTextField("8080");
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
                node = new ChatNode(nickname, advertisedIp, port);
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

        // Redirect System.out to chat
        redirectSystemOut();

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

        // Logo
        JLabel logo = createLogoLabel(48);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Status
        statusLabel = new JLabel("Status: Connected");
        statusLabel.setForeground(TEXT_COLOR);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        roomNameLabel = new JLabel("Room: " + node.getRoomName());
        roomNameLabel.setForeground(SKYPE_BLUE);
        roomNameLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        roomNameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Peers section
        JLabel peersLabel = createLabel("Online Peers");
        peersLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));

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

        JButton refreshBtn = createButton("Refresh", false);
        refreshBtn.setMaximumSize(new Dimension(200, 30));
        refreshBtn.addActionListener(e -> {
            refreshPeerList();
            refreshRoomList();
        });

        JButton resyncBtn = createButton("Resync History", false);
        resyncBtn.setMaximumSize(new Dimension(200, 30));
        resyncBtn.addActionListener(e -> resyncHistory());

        JButton sendFileBtn = createButton("Send File", false);
        sendFileBtn.setMaximumSize(new Dimension(200, 30));
        sendFileBtn.addActionListener(e -> sendFile());

        JButton robotBtn = createButton("Toggle Robot", false);
        robotBtn.setMaximumSize(new Dimension(200, 30));
        robotBtn.addActionListener(e -> toggleRobot());

        JButton disconnectBtn = createButton("Disconnect", false);
        disconnectBtn.setMaximumSize(new Dimension(200, 30));
        disconnectBtn.addActionListener(e -> disconnect());

        // Layout
        sidebar.add(logo);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(statusLabel);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(roomNameLabel);
        sidebar.add(Box.createVerticalStrut(15));
        sidebar.add(new JSeparator());
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(peersLabel);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(peerScroll);
        sidebar.add(Box.createVerticalStrut(15));
        sidebar.add(roomsLabel);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(roomScroll);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(joinBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(refreshBtn);
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(resyncBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(sendFileBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(robotBtn);
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(disconnectBtn);

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
                node.sendChatMessage(text);
            } else {
                appendChat("[System] Not connected to network.", TEXT_MUTED);
            }
        }
    }

    private void handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

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
            case "/resync" -> resyncHistory();
            case "/clear" -> chatPane.setText("");
            case "/robot" -> toggleRobot();
            case "/disconnect" -> disconnect();
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
        appendChat("/resync - Rebuild chat history", TEXT_MUTED);
        appendChat("/clear - Clear chat display", TEXT_MUTED);
        appendChat("/robot - Toggle robot chat", TEXT_MUTED);
        appendChat("/disconnect - Disconnect", TEXT_MUTED);
        appendChat("/help - Show this help", TEXT_MUTED);
        appendChat("/quit - Exit application", TEXT_MUTED);
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

    private void disconnect() {
        if (node != null && node.isRunning()) {
            stopRobot();
            node.stop();
            appendChat("[System] Disconnected from network.", TEXT_MUTED);
            statusLabel.setText("Status: Disconnected");
        }
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
                if (peer.isSwarmKey()) display += " [KEY]";
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
        PrintStream guiOut = new PrintStream(System.out) {
            @Override
            public void println(String x) {
                super.println(x);
                appendChat(x, TEXT_COLOR);
            }
        };
        System.setOut(guiOut);
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
