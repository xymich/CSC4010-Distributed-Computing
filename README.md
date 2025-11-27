# 🗨️ Slykord

> A Scalable Peer-to-Peer Chat System inspired by **Sl**ack, Sk**y**pe, and Disc**ord** (hence "Slykord")

📚 **CSC4010 Distributed Computing** - Assignment 2  
🎓 Master's Year Computer Science @ Queen's University Belfast

---

## ✨ Features

- **Decentralized Architecture** - No central server required
- **Swarm-based Routing** - Efficient message delivery through key nodes
- **GUI & CLI Modes** - Choose your preferred interface
- **File Sharing** - Send files directly to peers
- **Chat History Sync** - Automatically sync messages when joining
- **Room Discovery** - Find and join active chat rooms
- **Lamport Clocks** - Consistent message ordering across peers

---

## 🚀 Quick Start

### Prerequisites
- Java 11 or higher
- Compiled classes in the `bin/` directory

### Running the Application

**Interactive Mode** (recommended for first-time users):
```bash
java -cp bin ChatLauncher
```

**Direct Launch Options**:
```bash
# Launch with GUI (Skype-style interface)
java -cp bin ChatLauncher --gui

# Launch with CLI (Command Line Interface)
java -cp bin ChatLauncher --cli

# Show help
java -cp bin ChatLauncher --help
```

**Alternative Direct Launch**:
```bash
java -cp bin ChatGUI          # GUI only
java -cp bin SimpleChatClient # CLI only
```

---

## 🎮 CLI Commands

| Command | Description |
|---------|-------------|
| `/peers` | List connected peers |
| `/rooms` | Show discovered chat rooms |
| `/status` | Display node status |
| `/messages` | Show message history |
| `/resync` | Rebuild chat history from peers |
| `/clear` | Clear the screen |
| `/robot` | Toggle robot mode (auto-responses) |
| `/disconnect` | Leave the network gracefully |
| `/help` | Show all available commands |
| `/quit` | Exit the application |

> 💡 **Tip**: All CLI commands also work in the GUI by typing them in the message input!

---

## 🖼️ GUI Features

- **Dark Theme** - Skype-inspired interface
- **Peer Sidebar** - See who's online
- **Room Browser** - Discover and join rooms
- **File Sharing** - Easy drag-and-drop or file picker
- **Custom Branding** - Slykord's Unique Branding for all!

---

## 📁 Project Structure

```
├── ChatLauncher.java      # Main entry point
├── ChatGUI.java           # JavaFX GUI client
├── SimpleChatClient.java  # CLI client
├── ChatNode.java          # Core P2P node logic
├── SwarmManager.java      # Swarm coordination
├── UDPHandler.java        # Network communication
├── DiscoveryHandler.java  # Room discovery
├── FragmentAssembler.java # Large message handling
├── FileTransfer.java      # File sharing support
└── bin/                   # Compiled classes
```

---

## 🔧 Building from Source

```bash
# Compile all Java files
javac -d bin *.java

# Run the application
java -cp bin ChatLauncher
```

---

## 📝 Notes

- Chat logs are saved to the `logs/` directory
- Downloaded files are saved to the `downloads/` directory
- The system uses UDP for communication (default port auto-selected)

*Keep in mind - this README.md was made entirely with an LLM and there may be errors