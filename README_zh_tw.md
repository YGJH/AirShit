================================================================================
 AirShit - 區域網路 P2P 檔案傳輸工具
================================================================================


[英文](README.md)

--------------------------------------------------------------------------------

版本: 1.0.0 (根據 build.ps1 中的 AppVersion)
作者: AirShit Project (根據 build.ps1 中的 VendorName)
最後更新: 2025-05-18

--------------------------------------------------------------------------------
 簡介
--------------------------------------------------------------------------------

AirShit 是一套專為區域網路 (LAN) 環境設計的點對點 (P2P) 檔案傳輸應用程式。
它利用 TCP 協定進行穩定可靠的檔案傳輸，並透過 UDP 組播 (Multicast) 技術
實現區域網路內其他 AirShit 節點的自動發現與心跳維護。

本應用程式支援單一檔案、多個檔案以及整個資料夾 (包含其子目錄與檔案) 的傳輸。
為了提升大檔案的傳輸效率，AirShit 會根據 CPU 核心數量將大檔案切割成多個區塊 (chunks)，
並利用多執行緒進行並行傳輸。

圖形使用者介面 (GUI) 採用 Java Swing 技術實作，提供了直觀的檔案選擇功能、
可用的線上節點列表、清晰的檔案上傳與下載進度條，以及詳細的狀態日誌記錄，
方便使用者追蹤傳輸過程。

--------------------------------------------------------------------------------
 主要功能特色
--------------------------------------------------------------------------------

1.  **節點自動發現與維護:**
    *   使用 UDP Multicast (預設位址: 239.255.42.99，預設埠號: 50000) 在區域網路內
        廣播 `HELLO` 訊息以宣告自身存在。
    *   定期發送 `HEARTBEAT` 訊息並監聽其他節點的回應，以維護一份即時的線上節點列表。
    *   自動從列表中移除超時未回應的節點。

2.  **點對點 (P2P) 直接傳輸:**
    *   檔案傳輸在傳送端與接收端之間建立直接的 TCP 連線。
    *   傳輸流程嚴謹：建立連線 -> 傳送檔案元資訊 (檔名、大小等) -> 接收端確認 (ACK) ->
        分段傳輸檔案內容 -> 每一段傳輸完成後等待接收端確認 -> 所有分段傳輸完畢 ->
        最終確認。

3.  **支援多檔案與資料夾傳輸:**
    *   使用者可以透過 GUI 選擇單一檔案進行傳送。
    *   支援選擇整個資料夾，應用程式會遞迴地包含資料夾內的所有檔案及子資料夾結構進行傳輸。

4.  **多執行緒並行傳輸:**
    *   對於較大的檔案，會根據當前系統的 CPU 核心數量將檔案分割成多個區塊。
    *   每個區塊會由一個獨立的執行緒負責傳輸，從而實現並行處理，有效提升大檔案的傳輸速度。

5.  **圖形使用者介面 (GUI):**
    *   基於 Java Swing 開發，提供跨平台的圖形介面。
    *   **節點列表 (ClientPanel):** 動態顯示區域網路內偵測到的其他 AirShit 使用者。
    *   **檔案選擇 (FileSelectionPanel):** 方便使用者瀏覽並選擇要傳送的檔案或資料夾。
    *   **傳輸控制 (SendControlPanel):** 包含「傳送」按鈕，並根據是否選擇檔案和目標客戶端來啟用/禁用。
    *   **進度顯示 (ReceiveProgressPanel):** 以進度條形式顯示檔案接收或傳送的進度百分比。
    *   **狀態日誌 (LogPanel):** 即時輸出應用程式的運行狀態、節點發現、檔案傳輸事件及可能的錯誤訊息。
    *   **明暗模式切換:** 提供按鈕讓使用者在明亮模式和夜晚模式之間切換界面主題，提升使用體驗。

--------------------------------------------------------------------------------
 專案結構
--------------------------------------------------------------------------------

AirShit/
├── AirShit/                      # 主要的 Java 原始碼與編譯後的 .class 檔案根目錄
│   ├── Client.java               # 代表遠端客戶端節點的類別
│   ├── FileReceiver.java         # (可能與 Receiver.java 功能相關或為其一部分)
│   ├── FileSender.java           # (可能與 SendFile.java 功能相關或為其一部分)
│   ├── FolderSelector.java       # 處理資料夾選擇邏輯的輔助類別
│   ├── Main.java                 # 應用程式主入口類別，處理節點發現、心跳、多執行緒管理
│   ├── Receiver.java             # 處理檔案接收邏輯的類別，包含 ChunkReceiver
│   ├── SendFile.java             # 處理檔案傳送邏輯的類別，包含 ChunkSender
│   ├── SendFileGUI.java          # 主 GUI 視窗類別，整合所有 UI 面板
│   ├── TransferCallback.java     # (可能用於傳輸過程中的回呼介面)
│   │
│   ├── ui/                       # GUI 元件相關類別目錄
│   │   ├── ClientCellRenderer.java # 自訂 JList 中客戶端條目的渲染器
│   │   ├── ClientPanel.java      # 顯示可用客戶端列表的面板
│   │   ├── FileSelectionPanel.java # 檔案/資料夾選擇面板
│   │   ├── LogPanel.java         # 顯示狀態日誌的面板
│   │   ├── ReceiveProgressPanel.java # 顯示接收/傳送進度條的面板
│   │   └── SendControlPanel.java # 包含傳送按鈕的控制面板
│   │
│   └── (編譯後的 .class 檔案會與 .java 檔案在相同目錄結構下)
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
│       ├── SendFileGUI$ClientCellRenderer.class (應為 ui/ClientCellRenderer.class)
│       ├── TransferCallback.class
│       └── ui/
│           ├── ClientCellRenderer.class
│           ├── ClientPanel.class
│           ├── FileSelectionPanel.class
│           ├── LogPanel.class
│           ├── ReceiveProgressPanel.class
│           └── SendControlPanel.class
│
├── libs/                         # 存放外部函式庫 (例如 FlatLaf)
│   └── flatlaf-3.4.1.jar         # FlatLaf Look and Feel 函式庫
│
├── build.ps1                     # Windows PowerShell 建置腳本 (編譯、執行、打包、git 操作)
├── build.sh                      # Linux/macOS Bash 建置腳本 (編譯、執行、git 操作)
├── build_mac.sh                  # (可能為 macOS 特化的建置腳本)
├── bypass.cmd                    # Windows CMD 腳本，用於繞過 PowerShell 執行策略來執行 build.ps1
└── README.md                     # Markdown 格式的 README 檔案

(注意: 上述 .class 檔案列表是根據提供的 folder 內容推斷，實際編譯結果可能略有不同，
例如 SendFileGUI$ClientCellRenderer.class 應該位於 ui/ 子目錄下。)

--------------------------------------------------------------------------------
 建置與執行
--------------------------------------------------------------------------------

### 前置需求

*   **Java Development Kit (JDK):** 版本 8 或更高。建議安裝 JDK 11 或更新版本，以獲得 `jpackage` 工具的完整支援 (用於打包)。
*   **Git (可選):** 如果你需要從版本控制系統拉取程式碼或推送更新。
*   **作業系統:** Windows, macOS, 或 Linux。

### 一般建置與執行步驟 (使用提供的腳本)

#### Linux / macOS

1.  開啟終端機 (Terminal)。
2.  導覽到專案根目錄 (包含 `build.sh` 的目錄):
    `cd /path/to/AirShit`
3.  賦予腳本執行權限 (如果尚未設定):
    `chmod +x build.sh`
    `chmod +x build_mac.sh` (如果使用 macOS 專用腳本)
4.  **編譯並執行應用程式:**
    `./build.sh`
    或針對 macOS: `./build_mac.sh`
5.  **僅編譯 (如果腳本支援):** 查閱腳本內容，通常不帶參數執行即為編譯並運行。
6.  **執行 Git 推送 (push):**
    `./build.sh push`
7.  **執行 Git 拉取 (pull) 並重置:**
    `./build.sh pull`
8.  **打包應用程式 (如果腳本支援 `wraping` 或類似參數):**
    `./build.sh wraping` (此參數在你的腳本中用於建立 JAR)

#### Windows (使用 PowerShell)

1.  開啟 PowerShell。
2.  導覽到專案根目錄 (包含 `build.ps1` 的目錄):
    `cd C:\path\to\AirShit`
3.  **執行 PowerShell 腳本的權限:**
    如果遇到執行策略問題，可以臨時為當前處理程序設定執行權限：
    `Set-ExecutionPolicy Unrestricted -Scope Process -Force`
    或者，使用提供的 `bypass.cmd` 腳本來執行 `build.ps1` 中的命令。
4.  **編譯並執行應用程式 (預設動作):**
    `.\build.ps1`
    或透過 bypass: `.\bypass.cmd` (不帶參數，執行 default 區塊)
5.  **執行 Git 推送 (push):**
    `.\build.ps1 push`
    或透過 bypass: `.\bypass.cmd push`
6.  **執行 Git 拉取 (pull) 並重置:**
    `.\build.ps1 pull`
    或透過 bypass: `.\bypass.cmd pull`
7.  **打包應用程式為可執行安裝檔 (EXE):**
    `.\build.ps1 packing`
    或透過 bypass: `.\bypass.cmd packing`
    (這會使用 `jpackage`，請確保 JDK 包含此工具且環境變數設定正確)

### 手動編譯與執行 (不使用腳本)

假設你在專案根目錄 (`AirShit/`) 下：

1.  **編譯:**
    ```bash
    # Linux/macOS
    javac -cp ".:libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d . AirShit/*.java AirShit/ui/*.java

    # Windows
    javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d . AirShit\*.java AirShit\ui\*.java
    ```
    (注意: 上述命令假設 .java 檔案位於 `AirShit` 子目錄下，如果它們在根目錄，則移除 `AirShit/` 或 `AirShit\` 部分，並調整 `-d .` 為 `-d AirShit` 以便 .class 檔案輸出到正確的套件結構中。)
    根據你的 `.class` 檔案結構，更準確的編譯命令 (從 `AirShit/` 根目錄執行，且 .java 檔案在 `AirShit/AirShit/` 和 `AirShit/AirShit/ui/`):
    ```bash
    # Linux/macOS (從 AirShit/ 目錄執行)
    javac -cp ".:libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d . AirShit/*.java AirShit/ui/*.java

    # Windows (從 AirShit/ 目錄執行)
    javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d . AirShit\*.java AirShit\ui\*.java
    ```
    如果你的 .java 檔案實際上是在 `src/AirShit` 和 `src/AirShit/ui`，並且 `libs` 在 `src/libs`，則從 `src` 目錄執行：
    ```bash
    # (假設從 src 目錄執行)
    # javac -cp ".:libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d ../AirShit AirShit/*.java AirShit/ui/*.java
    ```
    **根據你提供的 `.class` 檔案結構，最可能的編譯命令 (從 `AirShit/` 根目錄執行，且 .java 檔案就在此根目錄和 `ui/` 子目錄下，輸出 .class 到相同位置):**
    ```bash
    # Linux/macOS (從 AirShit/ 目錄執行)
    javac -cp ".:libs/flatlaf-3.4.1.jar" -encoding UTF-8 *.java ui/*.java

    # Windows (從 AirShit/ 目錄執行)
    javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 *.java ui\*.java
    ```

2.  **執行:**
    ```bash
    # Linux/macOS
    java -cp ".:libs/flatlaf-3.4.1.jar" -Dfile.encoding=UTF-8 AirShit.Main

    # Windows
    java -cp ".;libs/flatlaf-3.4.1.jar" -Dfile.encoding=UTF-8 AirShit.Main
    ```


--------------------------------------------------------------------------------
 注意事項與已知問題
--------------------------------------------------------------------------------

*   **防火牆:** 請確保作業系統的防火牆允許 AirShit 使用的 TCP 埠號 (用於檔案傳輸，動態分配) 和 UDP 埠號 (預設 50000，用於節點發現) 的通訊。
*   **網路介面:** 在某些具有多個網路介面的系統上，UDP 組播可能需要正確配置或選擇特定的網路介面才能正常工作。`Main.java` 中的 `findCorrectNetworkInterface()` 嘗試處理此問題。
*   **編碼:** 應用程式內部及建置腳本均指定使用 UTF-8 編碼，以確保跨平台檔案名稱和訊息的正確處理。
*   **FlatLaf:** GUI 的美化依賴 FlatLaf 函式庫。請確保 `flatlaf-3.4.1.jar` (或更新版本) 位於正確的 `libs` 目錄下，並且 classpath 設定正確。
*   **jpackage (打包):** `jpackage` 工具是 JDK 14 及更高版本的一部分。如果使用較舊的 JDK，`packing` 功能可能無法使用。


## 注意事項

- build.sh
- build_mac.sh
- build.ps1


**這三個腳本在使用 pull 命令時，會直接 reset 你所有 local 端的更改，然後直接 pull 進本地端，所以請謹慎使用。**


================================================================================