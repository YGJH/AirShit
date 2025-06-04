# AirShit - 區域網路 P2P 檔案傳輸工具


[英文](README.md)

=====================================================

版本: 1.4.0

最後更新: 2025-05-30

=====================================================

# 注意
Release 1.2.0 為當前穩定版本；1.3.* 系列目前仍屬不穩定版本。
若未偵測到其他裝置，請在每臺設備的客戶端面板下方點擊「重新整理」按鈕。


## 簡介

### image
![AirShit Main Window](img/dark.png)
![light mode](img/light.png)
![File Transfer](img/fileexp.png)

AirShit 是一套專為區域網路 (LAN) 環境設計的點對點 (P2P) 檔案傳輸應用程式。
它利用 TCP 協定進行穩定可靠的檔案傳輸，並透過 UDP 組播 (Multicast) 技術
實現區域網路內其他 AirShit 節點的自動發現與心跳維護。

本應用程式支援單一檔案、多個檔案以及整個資料夾 (包含其子目錄與檔案) 的傳輸。
為了提升大檔案的傳輸效率，AirShit 會根據 CPU 核心數量將大檔案切割成多個區塊 (chunks)，
並利用多執行緒進行並行傳輸。

圖形使用者介面 (GUI) 採用 Java Swing 技術實作，提供了直觀的檔案選擇功能、
可用的線上節點列表、清晰的檔案上傳與下載進度條，以及詳細的狀態日誌記錄，
方便使用者追蹤傳輸過程。

## 主要功能特色


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

## 專案結構


```
AirShit/
├── README.md                           # 專案說明文件
├── README_zh_tw.md                     # 中文說明文件
├── pom.xml                             # Maven 建置配置
├── img/                                # 文件圖片
│   ├── dark.png                        # 深色主題截圖
│   ├── light.png                       # 淺色主題截圖
│   └── fileexp.png                     # 檔案總管截圖
│
└── src/
    └── main/
        ├── java/
        │   └── AirShit/                # 主要套件
        │       ├── Main.java           # 應用程式入口點 & 網路控制器
        │       ├── Client.java         # 客戶端資訊模型
        │       ├── TransferCallback.java # 檔案傳輸回調介面
        │       │
        │       ├── 📁 Network Layer (網路層)
        │       ├── FileReceiver.java   # 檔案接收器 (含接收確認對話框)
        │       ├── FileSender.java     # 檔案發送器
        │       ├── ReceiverOptimized.java # 優化的接收器 (支援區塊比對)
        │       ├── SendFileOptimized.java # 優化的發送器 (多執行緒)
        │       │
        │       ├── 📁 File Processing (檔案處理層)
        │       ├── TarCompressor.java  # TAR 壓縮工具
        │       ├── TarExtractor.java   # TAR 解壓縮工具
        │       ├── ChunkInfo.java      # 檔案區塊資訊
        │       │
        │       ├── 📁 Utilities (工具層)
        │       ├── FolderSelector.java # 資料夾選擇工具
        │       ├── GenerateTestFolder.java # 測試資料夾生成器
        │       ├── NoFileSelectedException.java # 自定義例外
        │       │
        │       ├── 📁 GUI Layer (圖形介面層)
        │       ├── SendFileGUI.java    # 主視窗 & 主題管理
        │       │
        │       └── ui/                 # UI 元件套件
        │           ├── LogPanel.java           # 日誌面板
        │           ├── ClientPanel.java        # 客戶端列表面板
        │           ├── FileSelectionPanel.java # 檔案選擇面板
        │           ├── SendControlPanel.java   # 發送控制面板
        │           ├── ReceiveProgressPanel.java # 接收進度面板
        │           ├── AnimationUtils.java     # 動畫工具類
        │           ├── FileChooserDialog.java  # 自定義檔案選擇器
        │           └── FXFileChooserAdapter.java # JavaFX 適配器
        │
        └── resources/                  # 資源檔案
            ├── asset/                  # 圖片資源
            │   ├── data-transfer.png   # 資料傳輸圖示
            │   ├── folder.png          # 資料夾圖示
            │   ├── kitty.icns          # macOS 應用程式圖示
            │   ├── kitty.ico           # Windows 應用程式圖示
            │   └── kitty.png           # 通用應用程式圖示
            │
            ├── css/                    # 樣式表
            │   ├── dark-theme.css      # 深色主題樣式
            │   └── file-chooser-dialog.css # 檔案選擇器樣式
            │
            └── icons/                  # 小圖示
                ├── up-arrow.png        # 向上箭頭
                └── slash.png           # 路徑分隔符號
```


(注意: 上述 .class 檔案列表是根據提供的 folder 內容推斷，實際編譯結果可能略有不同，
例如 SendFileGUI$ClientCellRenderer.class 應該位於 ui/ 子目錄下。)

## 建置與執行

### 前置需求
*   **maven**
*   **Java Development Kit (JDK):** 版本 8 或更高。建議安裝 JDK 11 或更新版本，以獲得 `jpackage` 工具的完整支援 (用於打包)。
*   **Git (可選):** 如果你需要從版本控制系統拉取程式碼或推送更新。
*   **作業系統:** Windows, macOS, 或 Linux。

### 一般建置與執行步驟 (使用提供的腳本)

#### Linux / macOS / windows

```command line
mvn compile exec:java
```


## 已知問題

*   **防火牆:** 請確保作業系統的防火牆允許 AirShit 使用的 TCP 埠號 (用於檔案傳輸，動態分配) 和 UDP 埠號 (預設 50000，用於節點發現) 的通訊。
*   **網路介面:** 在某些具有多個網路介面的系統上，UDP 組播可能需要正確配置或選擇特定的網路介面才能正常工作。`Main.java` 中的 `findCorrectNetworkInterface()` 嘗試處理此問題。
*   **編碼:** 應用程式內部及建置腳本均指定使用 UTF-8 編碼，以確保跨平台檔案名稱和訊息的正確處理。
*   **jpackage (打包):** `jpackage` 工具是 JDK 14 及更高版本的一部分。如果使用較舊的 JDK，`packing` 功能可能無法使用。


### packing

```windows
mvn clean package
# jpackage --type exe `
              --dest "dist" `
              --input "target"`
              --name "AirShit" `
              --main-jar "AirShit-1.0-SNAPSHOT/jar"`
              --java-options "-Dfile.encoding=UTF-8" `
              --app-version 1.3.4 `
              --vendor "AirShit Project" `
              --win-menu `
              --win-shortcut `
              --icon ".\src\main\resources\asset\kitty.ico" `
              --runtime-image "c:\Program Files\Java\jdk-21"
```

### packing_for_mac

```mac
mvn clean package
jpackage --type dmg \
             --dest ./dist \
             --input target \
             --name "AirShit" \
             --main-jar AirShit-1.0-SNAPSHOT.jar \
             --java-options "-Xmx512m" \
             --app-version 1.2.0 \
             --vendor "AirShit Project" \
             --icon src/main/resources/asset/kitty.icns
```
