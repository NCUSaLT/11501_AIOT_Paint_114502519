# Online Paint — 多人即時協作畫布（Android）

以 Android（Java）與 TCP Socket 實作的多人共同畫布。一台裝置執行 **Server**，其他裝置執行 **Client** 加入；每位使用者會被分配一個專屬顏色，畫的每一筆都會即時同步到所有人的畫面。

本專案分成兩個獨立的 Android 專案：

| 專案 | 角色 | 說明 |
|---|---|---|
| `Android_Paint_Server` | 伺服器 | 管理連線、保存筆跡、廣播給所有 Client、管理員功能（清除、踢人） |
| `Android_Paint_Client` | 使用者端 | 加入畫布、繪圖、撤銷、橡皮擦、離開 |

---

## 功能

### Server
- 輸入 Server 名稱與 Port 後啟動，畫面顯示本機 IP 與 Port
- 共用畫布，即時顯示所有人的筆跡
- 成員列表：名稱、IP、專屬顏色
- **清除所有筆跡**：清空整張畫布，所有 Client 同步
- **清除（單一成員）**：只清除該成員畫的筆跡
- **踢出**：將成員移出畫布，被踢的 Client 會收到通知
- 新成員加入時，會自動收到目前畫布上的所有筆跡
- 名稱重複時自動加上後綴（例如 `user_1_2`）

### Client
- 輸入名稱、Server IP、Port 後加入
- 加入後由 Server 分配專屬顏色，顯示在畫面上方
- 手指繪圖，所有人（包含自己）看到的畫面一致
- **撤銷**：移除自己最後畫的一筆（整筆）
- **橡皮擦**：手指碰到「自己畫的」某一筆就整筆刪除，無法擦除別人的筆跡
- **清除**：清除自己畫的所有筆跡
- **離開**：離開畫布（離開後該使用者的筆跡會一併移除）
- 通知區：顯示有人加入、離開、被踢出等訊息
- 連線失敗、Server 關閉或被踢出時，會顯示原因後返回

---

## 使用方式

### 1. 取得專案
```
git clone https://github.com/NCUSaLT/11501_AIOT_Paint_114502519
```
以 Android Studio 分別開啟 `Android_Paint_Server` 與 `Android_Paint_Client`，等待 Gradle Sync 完成。

### 2. 啟動 Server
1. 在裝置（或模擬器）上執行 Server App
2. 輸入 Server 名稱與 Port（預設 `8080`，範圍 1024 ~ 65535）
3. 按下啟動，記下畫面上顯示的 **IP:Port**

### 3. 啟動 Client
1. 在另一台裝置執行 Client App
2. 輸入名稱、Server IP、Port（需與 Server 相同）
3. 按「加入畫布」，即可開始繪圖

### 連線方式建議

| 情境 | Server IP 要填什麼 |
|---|---|
| 兩台**實機**接同一個 Wi-Fi（最簡單） | Server 畫面上顯示的 IP |
| Client 在**模擬器**，Server 在電腦 | `10.0.2.2`（模擬器連到電腦本機的特殊位址） |
| Server 與 Client **都在模擬器** | Client 填 `10.0.2.2`，並先在電腦執行 `adb -s <Server 模擬器序號> forward tcp:8080 tcp:8080` |

---

## 通訊協定

以 TCP 傳送文字，**一行一則訊息**，欄位以 `|` 分隔。繪圖座標為 **0 ~ 1 的比例值**，因此不同螢幕大小的裝置畫面位置也能對得上。

### Client → Server

| 訊息 | 說明 |
|---|---|
| `JOIN\|name` | 加入畫布 |
| `DRAW\|strokeId\|x1\|y1\|x2\|y2` | 畫一小段線，同一筆畫的 `strokeId` 相同 |
| `UNDO` | 撤銷自己最後一筆 |
| `ERASE\|strokeId` | 橡皮擦：整筆刪除自己的某一筆 |
| `CLEAR_MINE` | 清除自己所有筆跡 |
| `LEAVE` | 離開 |

### Server → Client

| 訊息 | 說明 |
|---|---|
| `WELCOME\|name\|color` | 加入成功，回傳最終名稱與專屬顏色 |
| `DRAW\|owner\|strokeId\|x1\|y1\|x2\|y2\|color` | 廣播一小段線（包含畫的人自己） |
| `UNDO\|owner\|strokeId` | 移除某人的某一筆（撤銷與橡皮擦共用） |
| `CLEAR_USER\|owner` | 清除某人所有筆跡 |
| `CLEAR_ALL` | 清空整張畫布 |
| `NOTICE\|text` | 通知訊息 |
| `KICKED` | 被管理員踢出 |

---

## 設計說明

- **以 Server 為唯一資料來源**：Client 觸控時只負責把筆跡送給 Server，真正畫上畫面的是 Server 廣播回來的資料，所以所有人的畫面一定一致，撤銷與清除也不會出現重複筆跡。
- **執行緒設計**
  - Server 的所有狀態（成員、筆跡）只在 UI 執行緒讀寫，不需要加鎖。
  - 每個連線一條讀取執行緒，只負責 `readLine`，再把訊息交回 UI 執行緒處理。
  - 每個連線一條單執行緒寫出佇列，網路寫入不會發生在 UI 執行緒，訊息順序也不會亂。
  - Client 同樣以單一寫出佇列送出訊息。
- **權限檢查**：橡皮擦與撤銷只會在「發送者自己的筆跡」中尋找，無法影響他人。
- **旋轉螢幕**：兩個 App 的畫布頁面皆不會因旋轉而重建，避免連線被中斷。

---

## 專案結構

```
.
├── Android_Paint_Server/
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/android_paint_server/
│       │   ├── MainActivity.java          # 輸入 Server 名稱與 Port
│       │   ├── PaintServerActivity.java   # Server 主體：連線管理、訊息處理、成員列表
│       │   └── SharedCanvasView.java      # 共用畫布與筆跡歷史
│       └── res/layout/
│           ├── activity_main.xml
│           ├── activity_paint_server.xml
│           └── item_client_row.xml        # 成員列表的每一列
└── Android_Paint_Client/
    └── app/src/main/
        ├── AndroidManifest.xml            # 需要 INTERNET 權限
        ├── java/com/example/android_paint_client/
        │   ├── MainActivity.java          # 輸入名稱、IP、Port
        │   ├── PaintClientActivity.java   # 連線、收發訊息、按鈕功能
        │   └── ClientCanvasView.java      # 畫布、觸控、橡皮擦命中判斷
        └── res/layout/
            ├── activity_main.xml
            └── activity_paint_client.xml
```

---

## 已知限制

- 通訊為未加密的純 TCP，且沒有身分驗證，僅適合在區域網路內使用。
- 筆跡只保存在 Server 記憶體中，Server 關閉後不會保留。
- 畫布以比例座標同步，若兩台裝置的畫布長寬比差異很大，圖形會被拉伸。
- 使用者離開或被踢出時，其筆跡會一併移除。
- Port 需為 1024 以上（Android 一般應用程式無法使用更低的 Port）。
