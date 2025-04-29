# AirShit

AirShit 是一套在區域網路 (LAN) 下，以 P2P 方式使用 TCP 進行檔案傳輸的應用程式。  
採用組播 (Multicast) 進行節點廣播與發現，並使用多執行緒切割大檔後並行傳輸，支援單檔與多檔／資料夾傳送。  
GUI 端提供直觀的檔案選取、進度顯示與狀態日誌。

---

## 主要功能

- 節點自動發現  
  使用 UDP Multicast (239.255.42.99:50000) 互相廣播 `HELLO` 與心跳訊息，維護線上節點列表。  
- P2P 直連傳輸  
  傳輸端與接收端以 TCP 直連，並以「握手 → ACK → 分段傳輸 → 確認」流程完成檔案傳輸。  
- 多檔／資料夾支援  
  可選擇單一檔案或整個資料夾（包含子檔案與子資料夾）進行傳送。  
- 多執行緒切割傳輸  
  大檔案會依 CPU 核心數切割為多段，並行傳送提升效率。  
- GUI 介面  
  Swing 實作的檔案選擇、節點列表、上傳／下載進度條與狀態日誌。

---

## 專案結構

```
AirShit/
├── .gitignore
├── README.txt
├── bypass.cmd
├── build.sh
├── build_mac.sh
├── build.ps1
├── FolderSelector.java
├── TransferCallback.java
├── Client.java
├── Main.java
├── FileReceiver.java
├── FileSender.java
├── Receiver.java
├── SendFile.java
├── SendFileGUI.java
└── asset/
    ├── icon.jpg
    └── user.png
```
## 建置與執行

### 前置需求

- Java 8 以上 (建議安裝 JDK 11+)
- Git (可選，用於版本控制與 push/pull)
- Windows / macOS / Linux 均可執行

### Linux / macOS
(maxOS) 可能要用build_max.sh

```bash
cd c:/Users/kkasdasd/Documents/tt/AirShit
# 編譯並執行（默認 UTF-8 編碼）
./build.sh
# 推送更新
./build.sh push
# 測試流程
./build.sh test
```


### WIndows
```cmd
cd c:/Users/kkasdasd/Documents/tt/AirShit
# 預設編譯執行
.\build.ps1
# 推送
.\build.ps1 push
# 測試
.\build.ps1 test
# 或使用 bypass.cmd 避開執行政策
.\bypass.cmd test
```
或者可以直接
```cmd
Set-ExecutionPolicy Unrestricted -Scope Process -Force
```
讓powerShell可以執行腳本



