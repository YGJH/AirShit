# AirShit - Local Area Network P2P File Transfer Tool

[中文](README_zh_tw.md)

=====================================================

Version: 1.0.0

Last Updated: 2025-05-18

=====================================================


## Introduction

AirShit is a peer-to-peer (P2P) file transfer application designed specifically
for Local Area Network (LAN) environments. It utilizes the TCP protocol for
stable and reliable file transfers and employs UDP multicast technology for
automatic discovery and heartbeat maintenance of other AirShit nodes within the LAN.

This application supports the transfer of single files, multiple files, and
entire folders (including their subdirectories and files). To enhance the
transfer efficiency of large files, AirShit segments large files into multiple
chunks based on the number of CPU cores and utilizes multi-threading for
parallel transmission.

The Graphical User Interface (GUI) is implemented using Java Swing technology,
providing intuitive file selection, a list of available online nodes, clear
upload and download progress bars, and detailed status logging, making it
convenient for users to track the transfer process.

## Main Features

1.  **Automatic Node Discovery and Maintenance:**
    *   Uses UDP Multicast (default address: 239.255.42.99, default port: 50000)
        to broadcast `HELLO` messages within the LAN to announce its presence.
    *   Periodically sends `HEARTBEAT` messages and listens for responses from
        other nodes to maintain a real-time list of online nodes.
    *   Automatically removes nodes that do not respond within a timeout period
        from the list.

2.  **Peer-to-Peer (P2P) Direct Transfer:**
    *   File transfers establish a direct TCP connection between the sender and
        receiver.
    *   Rigorous transfer process: Establish connection -> Send file metadata
        (filename, size, etc.) -> Receiver acknowledgment (ACK) -> Segmented
        transfer of file content -> Wait for receiver acknowledgment after each
        segment transfer -> Final confirmation after all segments are transferred.

3.  **Support for Multiple Files and Folder Transfers:**
    *   Users can select a single file for sending via the GUI.
    *   Supports selecting an entire folder; the application will recursively
        include all files and subfolder structures within the folder for transfer.

4.  **Multi-threaded Parallel Transfer:**
    *   For larger files, the file is divided into multiple chunks based on the
        current system's CPU core count.
    *   Each chunk is handled by an independent thread for transfer, enabling
        parallel processing and effectively increasing the transfer speed of
        large files.

5.  **Graphical User Interface (GUI):**
    *   Developed with Java Swing, providing a cross-platform graphical interface.
    *   **Node List (ClientPanel):** Dynamically displays other AirShit users
        detected on the LAN.
    *   **File Selection (FileSelectionPanel):** Allows users to easily browse
        and select files or folders to send.
    *   **Transfer Control (SendControlPanel):** Contains the "Send" button,
        enabled/disabled based on whether a file and target client are selected.
    *   **Progress Display (ReceiveProgressPanel):** Shows the progress percentage
        of file reception or transmission as a progress bar.
    *   **Status Log (LogPanel):** Outputs real-time application status, node
        discovery, file transfer events, and potential error messages.
    *   **Light/Dark Mode Toggle:** Provides a button for users to switch
        between light and dark interface themes, enhancing user experience.

## Project Structure


```
AirShit/
├── AirShit/                      # Main Java source code and compiled .class file root directory
│   ├── Client.java               # Class representing a remote client node
│   ├── FileReceiver.java         # (Possibly related to or part of Receiver.java functionality)
│   ├── FileSender.java           # (Possibly related to or part of SendFile.java functionality)
│   ├── FolderSelector.java       # Helper class for folder selection logic
│   ├── Main.java                 # Main application entry point class, handles node discovery, heartbeat, multi-threading
│   ├── Receiver.java             # Class handling file reception logic, includes ChunkReceiver
│   ├── SendFile.java             # Class handling file sending logic, includes ChunkSender
│   ├── SendFileGUI.java          # Main GUI window class, integrates all UI panels
│   ├── TransferCallback.java     # (Possibly an interface for callbacks during transfer)
│   │
│   ├── ui/                       # Directory for GUI component related classes
│   │   ├── ClientCellRenderer.java # Custom renderer for client entries in JList
│   │   ├── ClientPanel.java      # Panel displaying available client list
│   │   ├── FileSelectionPanel.java # File/folder selection panel
│   │   ├── LogPanel.java         # Panel displaying status logs
│   │   ├── ReceiveProgressPanel.java # Panel displaying receive/send progress bar
│   │   └── SendControlPanel.java # Control panel containing the send button
│   │
│   └── (Compiled .class files will be in the same directory structure as .java files)
│       ├── Client.class
│       ├── FileReceiver.class
│       ├── FileSender.class
│       ├── FolderSelector.class
│       ├── Main.class
│       ├── Main$1.class
│       ├── Main$SEND_STATUS.class
│       ├── Receiver.class
│       ├── Receiver$ChunkReceiver.class
│       ├── SendFile.class
│       ├── SendFile$ChunkSender.class
│       ├── SendFileGUI.class
│       ├── SendFileGUI$1.class
│       ├── SendFileGUI$2.class
│       ├── SendFileGUI$ClientCellRenderer.class (Should be ui/ClientCellRenderer.class)
│       ├── TransferCallback.class
│       └── ui/
│           ├── ClientCellRenderer.class
│           ├── ClientPanel.class
│           ├── FileSelectionPanel.class
│           ├── LogPanel.class
│           ├── ReceiveProgressPanel.class
│           └── SendControlPanel.class
│
├── libs/                         # Directory for external libraries (e.g., FlatLaf)
│   └── flatlaf-3.4.1.jar         # FlatLaf Look and Feel library
│
├── build.ps1                     # Windows PowerShell build script (compile, run, package, git operations)
├── build.sh                      # Linux/macOS Bash build script (compile, run, git operations)
├── build_mac.sh                  # (Possibly a macOS-specific build script)
├── bypass.cmd                    # Windows CMD script to bypass PowerShell execution policy for build.ps1
└── README.md                     # Markdown formatted README file (This file)
```

(Note: The .class file list above is inferred from the provided folder content.
The actual compilation result might differ slightly, e.g.,
SendFileGUI$ClientCellRenderer.class should be under the ui/ subdirectory.)



## Build and Execution

### Prerequisites

*   **Java Development Kit (JDK):** Version 8 or higher. JDK 11 or newer is
    recommended for full `jpackage` tool support (for packaging).
*   **Git (Optional):** If you need to pull code from a version control system
    or push updates.
*   **Operating System:** Windows, macOS, or Linux.

### General Build and Execution Steps (Using Provided Scripts)

#### Linux / macOS

1.  Open Terminal.
2.  Navigate to the project root directory (containing `build.sh`):
    `cd /path/to/AirShit`
3.  Grant execution permission to the script (if not already set):
    `chmod +x build.sh`
    `chmod +x build_mac.sh` (if using the macOS specific script)
4.  **Compile and run the application:**
    `./build.sh`
    Or for macOS: `./build_mac.sh`
5.  **Compile only (if supported by the script):** Check script content; usually,
    running without arguments compiles and runs.
6.  **Execute Git push:**
    `./build.sh push`
7.  **Execute Git pull and reset:**
    `./build.sh pull`
8.  **Package the application (if the script supports `wraping` or similar):**
    `./build.sh wraping` (This parameter is used in your script to create a JAR)

#### Windows (Using PowerShell)

1.  Open PowerShell.
2.  Navigate to the project root directory (containing `build.ps1`):
    `cd C:\path\to\AirShit`
3.  **PowerShell script execution permission:**
    If you encounter execution policy issues, you can temporarily set the
    execution policy for the current process:
    `Set-ExecutionPolicy Unrestricted -Scope Process -Force`
    Alternatively, use the provided `bypass.cmd` script to execute commands
    from `build.ps1`.
4.  **Compile and run the application (default action):**
    `.\build.ps1`
    Or via bypass: `.\bypass.cmd` (without arguments, executes the default block)
5.  **Execute Git push:**
    `.\build.ps1 push`
    Or via bypass: `.\bypass.cmd push`
6.  **Execute Git pull and reset:**
    `.\build.ps1 pull`
    Or via bypass: `.\bypass.cmd pull`
7.  **Package the application as an executable installer (EXE):**
    `.\build.ps1 packing`
    Or via bypass: `.\bypass.cmd packing`
    (This uses `jpackage`; ensure your JDK includes this tool and environment
    variables are set correctly.)

### Manual Compilation and Execution (Without Scripts)

Assuming you are in the project root directory (`AirShit/`):

1.  **Compile:**
    Based on your `.class` file structure, the most likely compilation command (executed from the `AirShit/` root directory, with .java files in this root and the `ui/` subdirectory, outputting .class files to the same locations):
    ```bash
    # Linux/macOS (from AirShit/ directory)
    javac -cp ".:libs/flatlaf-3.4.1.jar" -encoding UTF-8 *.java ui/*.java

    # Windows (from AirShit/ directory)
    javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 *.java ui\*.java
    ```

2.  **Execute:**
    ```bash
    # Linux/macOS
    java -cp ".:libs/flatlaf-3.4.1.jar" -Dfile.encoding=UTF-8 AirShit.Main

    # Windows
    java -cp ".;libs/flatlaf-3.4.1.jar" -Dfile.encoding=UTF-8 AirShit.Main
    ```

## Issues


*   **Firewall:** Ensure your operating system's firewall allows communication
    on the TCP ports used by AirShit (dynamically allocated for file transfer)
    and UDP port (default 50000, for node discovery).
*   **Network Interface:** On systems with multiple network interfaces, UDP
    multicast might require correct configuration or selection of a specific
    network interface to work properly. `Main.java`'s
    `findCorrectNetworkInterface()` attempts to handle this.
*   **Encoding:** The application internally and the build scripts specify UTF-8
    encoding to ensure correct handling of cross-platform file names and messages.
*   **FlatLaf:** GUI theming relies on the FlatLaf library. Ensure
    `flatlaf-3.4.1.jar` (or a newer version) is in the correct `libs` directory
    and the classpath is set correctly.
*   **jpackage (Packaging):** The `jpackage` tool is part of JDK 14 and later.
    The `packing` feature might not be available if using an older JDK.


## Warning

- build.sh
- build_mac.sh
- build.ps1

**This three scripts will reset all of your modify which in your local file, and pull new commit to your local file. Please use it carefully.**
