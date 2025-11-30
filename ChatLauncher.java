import java.util.Scanner;

/**
 * Main launcher for P2P Chat application.
 * Allows user to choose between GUI (JavaFX) or CLI mode at startup.
 */
public class ChatLauncher {

    public static void main(String[] args) {
        // Check for command line arguments first
        if (args.length > 0) {
            String mode = args[0].toLowerCase();
            if (mode.equals("--gui") || mode.equals("-g")) {
                launchGUI(stripFirstArg(args));
                return;
            } else if (mode.equals("--cli") || mode.equals("-c")) {
                launchCLI(stripFirstArg(args));
                return;
            } else if (mode.equals("--help") || mode.equals("-h")) {
                printHelp();
                return;
            }
        }

        // Interactive mode selection
        System.out.println("===========================================");
        System.out.println("       P2P Distributed Chat System         ");
        System.out.println("===========================================");
        System.out.println();
        System.out.println("Select interface mode:");
        System.out.println("  1. GUI (Graphical - Skype-style interface)");
        System.out.println("  2. CLI (Command Line Interface)");
        System.out.println();
        System.out.print("Enter choice [1/2]: ");

        Scanner scanner = new Scanner(System.in);
        String choice = scanner.nextLine().trim();

        switch (choice) {
            case "1", "gui", "g" -> launchGUI(args);
            case "2", "cli", "c" -> launchCLI(args);
            default -> {
                System.out.println("Invalid choice. Defaulting to CLI mode.");
                launchCLI(args);
            }
        }
    }

    private static void launchGUI(String[] args) {
        System.out.println("Launching GUI mode...");
        System.out.println();
        
        // JavaFX requires launching via Application.launch()
        ChatGUI.main(args);
    }

    private static void launchCLI(String[] args) {
        System.out.println("Launching CLI mode...");
        System.out.println();
        SimpleChatClient.main(args);
    }

    private static String[] stripFirstArg(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] result = new String[args.length - 1];
        System.arraycopy(args, 1, result, 0, args.length - 1);
        return result;
    }

    private static void printHelp() {
        System.out.println("P2P Distributed Chat System");
        System.out.println();
        System.out.println("Usage: java ChatLauncher [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --gui, -g    Launch in GUI mode (JavaFX Skype-style interface)");
        System.out.println("  --cli, -c    Launch in CLI mode (Command Line Interface)");
        System.out.println("  --help, -h   Show this help message");
        System.out.println();
        System.out.println("If no option is provided, you will be prompted to choose.");
        System.out.println();
        System.out.println("GUI Features:");
        System.out.println("  - Skype-inspired dark theme interface");
        System.out.println("  - Sidebar with peer list and room discovery");
        System.out.println("  - File sending via file chooser dialog");
        System.out.println("  - Custom logo support (place 'logo.png' in app directory)");
        System.out.println();
        System.out.println("CLI Commands (also work in GUI via /command):");
        System.out.println("  /peers, /rooms, /status, /messages, /resync,");
        System.out.println("  /clear, /robot, /disconnect, /help, /quit");
    }
}
