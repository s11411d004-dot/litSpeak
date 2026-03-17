

**LitSpeak**

後端規劃書

Spring Boot \+ Railway \+ Supabase

v1.3  ｜  2025年  ｜  配合 App 設計文檔 v2.4

| *v1.3 更新：部署平台從 Railway 改為 Koyeb（免費、不需信用卡、永久有效）；移除 railway.toml；新增 system.properties（指定 Java 17）；更新部署流程說明。* |
| :---- |

# **1\. 架構總覽**

## **1.1 技術選型**

| 層次 | 技術 | 原因 |
| :---- | :---- | :---- |
| 後端框架 | Spring Boot (Java) | 開發者熟悉 Java 後端，學習成本最低 |
| 部署平台 | Koyeb（免費方案） | 零運維、自動部署、不需信用卡、永久免費、商業使用允許 |
| CI/CD | GitHub → Koyeb 自動部署 | 每次 push 自動重新部署，不需額外設定 |
| 雲端資料庫 | Supabase（PostgreSQL） | 免費方案慷慨、有管理介面、支援 REST API |
| App 本地資料庫 | SQLite \+ Room ORM | 離線可用、不需帳號、資料存裝置本地 |
| 記憶體快取 | Caffeine Cache（Spring Boot 內建） | 快取已驗證的 device\_id，減少 Supabase 查詢次數 |
| TTS 服務 | Azure Cognitive Services TTS（官方 REST API） | 穩定、有 SLA、與評量同一 Azure 帳號 |
| 翻譯服務 | Azure Translator | 每月 2M 字元免費，費率 $10/百萬（Google 一半）；與 TTS 同 Azure 帳號 |
| 評量服務 | Azure Speech Pronunciation Assessment（MVP 即支援） | 與 TTS 同一 API Key，按秒計費 |

## **1.2 後端職責**

後端只做三件事，保持最小化：

* 中轉 TTS 請求（保護 Azure TTS API Key）

* 中轉翻譯請求（保護 Azure Translator Key）

* 中轉評量請求（保護 Azure Speech Key，接收錄音送 Azure，不儲存錄音）

* 提供官方內容庫 API（MVP 先回傳空陣列）

* 記錄用量與裝置資訊到 Supabase（防濫用、未來商業化用）

## **1.3 資料分工**

| 資料類型 | 存放位置 | 說明 |
| :---- | :---- | :---- |
| 書籍文字、分句、進度 | App 本地 SQLite | 離線可用，不需同步 |
| TTS 音檔快取 | App 本地 SQLite \+ 檔案系統 | 避免重複計費 |
| 翻譯快取 | App 本地 SQLite | 避免重複計費 |
| 播放方案、設定 | App 本地 SQLite | 用戶個人設定 |
| 裝置 ID、用量記錄 | Supabase | 防濫用、用量追蹤 |
| 官方內容庫 | Supabase | 後端統一管理，App 下載後存本地 |
| 付款記錄（預留） | Supabase | 第二階段商業化用 |

# **2\. Supabase 資料庫 Schema**

## **2.1 裝置表 devices**

記錄每個使用 App 的裝置，用匿名 ID 識別，不需要用戶帳號。

| CREATE TABLE devices ( |
| :---- |
|   id            UUID PRIMARY KEY DEFAULT gen\_random\_uuid(), |
|   device\_id     TEXT UNIQUE NOT NULL,  \-- App 首次啟動產生，存本地 |
|   platform      TEXT NOT NULL,          \-- android / ios |
|   app\_version   TEXT NOT NULL,          \-- e.g. 1.0.0 |
|   first\_seen\_at TIMESTAMPTZ DEFAULT NOW(), |
|   last\_seen\_at  TIMESTAMPTZ DEFAULT NOW() |
| ); |

## **2.2 用量記錄表 usage\_logs**

每次呼叫 TTS 或翻譯 API 就寫一筆，記錄詳細資訊供追查。

| CREATE TABLE usage\_logs ( |
| :---- |
|   id             UUID PRIMARY KEY DEFAULT gen\_random\_uuid(), |
|   device\_id      TEXT NOT NULL REFERENCES devices(device\_id), |
|   timestamp      TIMESTAMPTZ DEFAULT NOW(), |
|   service        TEXT NOT NULL,   \-- tts / translate |
|   content\_type   TEXT,            \-- book / article / collection |
|   chars\_used     INTEGER NOT NULL, |
|   status         TEXT NOT NULL    \-- success / error |
| ); |
|   |
| \-- 索引：加速按裝置和月份查詢用量 |
| CREATE INDEX idx\_usage\_device\_time ON usage\_logs(device\_id, timestamp); |

## **2.3 官方內容庫表 official\_content**

後端管理的可下載內容，MVP 階段先建表但不上架資料。

| CREATE TABLE official\_content ( |
| :---- |
|   id           UUID PRIMARY KEY DEFAULT gen\_random\_uuid(), |
|   type         TEXT NOT NULL,        \-- book / article / collection |
|   title        TEXT NOT NULL, |
|   description  TEXT, |
|   tags         TEXT\[\],               \-- e.g. {business, interview} |
|   difficulty   TEXT,                 \-- beginner / intermediate / advanced |
|   download\_url TEXT NOT NULL, |
|   has\_human\_audio BOOLEAN DEFAULT FALSE, |
|   is\_published BOOLEAN DEFAULT FALSE, \-- false \= 草稿，不回傳給 App |
|   created\_at   TIMESTAMPTZ DEFAULT NOW() |
| ); |

## **2.4 付款記錄表 payments（預留，第二階段）**

| \-- 第二階段商業化時建立 |
| :---- |
| CREATE TABLE payments ( |
|   id           UUID PRIMARY KEY DEFAULT gen\_random\_uuid(), |
|   device\_id    TEXT NOT NULL REFERENCES devices(device\_id), |
|   plan         TEXT NOT NULL,        \-- monthly / lifetime |
|   amount       DECIMAL(10,2), |
|   currency     TEXT DEFAULT 'USD', |
|   status       TEXT NOT NULL,        \-- pending / success / failed |
|   paid\_at      TIMESTAMPTZ, |
|   created\_at   TIMESTAMPTZ DEFAULT NOW() |
| ); |

# **3\. 裝置 ID 設計**

## **3.1 產生方式**

App 第一次啟動時產生一個 UUID，永久存在本地（SharedPreferences \+ Android Keystore 加密）。之後每次呼叫後端 API 都帶上這個 ID。

| // App 端（Kotlin） |
| :---- |
| fun getOrCreateDeviceId(context: Context): String { |
|   val prefs \= context.getSharedPreferences("litspeak", Context.MODE\_PRIVATE) |
|   return prefs.getString("device\_id", null) ?: run { |
|     val newId \= UUID.randomUUID().toString() |
|     prefs.edit().putString("device\_id", newId).apply() |
|     newId |
|   } |
| } |

## **3.2 首次註冊**

App 第一次啟動後，呼叫後端 POST /api/devices 完成裝置註冊。後端寫入 Supabase devices 表。之後所有請求 header 都帶 X-Device-ID。

| *裝置 ID 不是用戶帳號，沒有個人識別資訊。用戶換手機後會產生新的 ID，本地資料（書籍、進度）無法跟著轉移，這是 MVP 階段的已知限制。* |
| :---- |

# **4\. API 端點設計**

## **4.1 共用規則**

| 項目 | 規格 |
| :---- | :---- |
| Base URL | https://litspeak-api.railway.app |
| Content-Type | application/json（除 TTS 回傳音檔外） |
| 認證方式 | 每個請求 Header 帶 X-Device-ID |
| 回應格式 | { success: bool, data: {...}, error: string } |
| 時區 | 所有時間欄位使用 UTC ISO 8601 |

## **4.2 POST /api/devices  裝置註冊**

| 項目 | 說明 |
| :---- | :---- |
| 用途 | App 首次啟動時呼叫，建立裝置記錄 |
| Request Headers | Content-Type: application/json |
| Request Body | { device\_id, platform, app\_version } |
| Response 200 | { success: true, data: { device\_id, first\_seen\_at } } |
| Response 409 | 裝置已存在，更新 last\_seen\_at，回傳 success: true |
| 冪等性 | 重複呼叫安全，用 upsert 處理 |

| // Request |
| :---- |
| POST /api/devices |
| { |
|   "device\_id": "550e8400-e29b-41d4-a716-446655440000", |
|   "platform": "android", |
|   "app\_version": "1.0.0" |
| } |
|   |
| // Response 200 |
| { |
|   "success": true, |
|   "data": { |
|     "device\_id": "550e8400-e29b-41d4-a716-446655440000", |
|     "first\_seen\_at": "2025-01-15T08:30:00Z" |
|   } |
| } |

## **4.3 POST /api/tts  文字轉語音**

| 項目 | 說明 |
| :---- | :---- |
| 用途 | 將文字送到 Azure Cognitive Services TTS，回傳 MP3 音檔 |
| Request Headers | X-Device-ID: {device\_id} |
| Request Body | { text, voice\_code, speed } |
| voice\_code 範例 | zh-TW-HsiaoChenNeural / en-US-JennyNeural / en-US-GuyNeural |
| speed 範例 | \-40 / \-20 / 0 / 20（對應最慢/慢/正常/快） |
| Response 200 | Content-Type: audio/mpeg，回傳 MP3 二進位資料 |
| Response 400 | 文字為空、voice\_code 不合法 |
| Response 429 | 該裝置本月用量超過上限 |
| Response 503 | Azure TTS 服務異常 |
| 後端呼叫 Azure 的格式 | POST https://{region}.tts.speech.microsoft.com/cognitiveservices/v1 |
| Azure Header | Ocp-Apim-Subscription-Key: {key}  /  Content-Type: application/ssml+xml  /  X-Microsoft-OutputFormat: audio-16khz-128kbitrate-mono-mp3 |
| SSML Body 範例 | \<speak version=1.0\>\<voice name=en-US-JennyNeural\>\<prosody rate=-20%\>{text}\</prosody\>\</voice\>\</speak\> |

| // Request |
| :---- |
| POST /api/tts |
| X-Device-ID: 550e8400-e29b-41d4-a716-446655440000 |
| { |
|   "text": "It was the best of times, it was the worst of times.", |
|   "voice\_code": "en-US-JennyNeural", |
|   "speed": \-20 |
| } |
|   |
| // Response 200 |
| Content-Type: audio/mpeg |
| \[MP3 binary data\] |
|   |
| // Response 429 |
| { |
|   "success": false, |
|   "error": "QUOTA\_EXCEEDED", |
|   "message": "Monthly TTS quota exceeded" |
| } |

## **4.4 POST /api/translate  翻譯**

| 項目 | 說明 |
| :---- | :---- |
| 用途 | 將文字批次送到 Azure Translator，回傳翻譯 |
| Request Headers | X-Device-ID: {device\_id} |
| Request Body | { sentences: \[string\], target\_lang: "zh-TW" } |
| 批次上限 | 每次最多 10 句（對應 App 端的分段策略） |
| Response 200 | { success: true, data: { translations: \[string\] } } |
| Response 400 | sentences 為空或超過 10 句 |
| Response 429 | 該裝置本月翻譯用量超過上限 |
| 後端呼叫 Azure 的格式 | POST https://api.cognitive.microsofttranslator.com/translate?api-version=3.0\&to={target\_lang} |
| Azure Header | Ocp-Apim-Subscription-Key: {AZURE\_TRANSLATOR\_KEY}  /  Ocp-Apim-Subscription-Region: {region} |
| Azure 請求 Body | \[{ Text: sentence }, ...\]（陣列格式） |
| Azure 回應格式 | \[{ translations: \[{ text, to }\] }, ...\] |

| // Request |
| :---- |
| POST /api/translate |
| X-Device-ID: 550e8400-e29b-41d4-a716-446655440000 |
| { |
|   "sentences": \[ |
|     "It was the best of times.", |
|     "It was the worst of times." |
|   \], |
|   "target\_lang": "zh-TW" |
| } |
|   |
| // Response 200 |
| { |
|   "success": true, |
|   "data": { |
|     "translations": \[ |
|       "這是最美好的時代。", |
|       "這是最糟糕的時代。" |
|     \] |
|   } |
| } |

## **4.5 GET /api/content-library  官方內容庫**

| 項目 | 說明 |
| :---- | :---- |
| 用途 | 回傳官方可下載的內容清單 |
| Request Headers | X-Device-ID: {device\_id} |
| Query Params | type（可選）: book / article / collection |
| Response 200 | { success: true, data: { items: \[...\] } } |
| MVP 行為 | is\_published \= false 的內容不回傳；MVP 先回傳空陣列 |

| // Request |
| :---- |
| GET /api/content-library?type=collection |
| X-Device-ID: 550e8400-e29b-41d4-a716-446655440000 |
|   |
| // Response 200 |
| { |
|   "success": true, |
|   "data": { |
|     "items": \[ |
|       { |
|         "id": "uuid", |
|         "type": "collection", |
|         "title": "面試英文 50 句", |
|         "description": "常見面試問題與回答示範", |
|         "tags": \["business", "interview"\], |
|         "difficulty": "intermediate", |
|         "download\_url": "https://...", |
|         "has\_human\_audio": false |
|       } |
|     \] |
|   } |
| } |

## **4.5b POST /api/assessment  發音評量**

| 項目 | 說明 |
| :---- | :---- |
| 用途 | 接收 App 錄音，送至 Azure Speech 發音評量，回傳分數 |
| Request Headers | X-Device-ID: {device\_id}  /  Content-Type: multipart/form-data |
| Request Body | audio: WAV 檔案（16kHz 16-bit mono）、reference\_text: 參考文字、sentence\_id: 句子 ID |
| 後端處理 | 接收錄音 → 送 Azure Pronunciation Assessment → 解析 JSON → 回傳分數 → 丟棄錄音 |
| 後端不儲存 | 錄音檔在後端記憶體處理完即丟棄，不寫入任何儲存空間 |
| Response 200 | { overall\_score, word\_scores: \[{word, score, phonemes: \[{phoneme, score}\]}\] } |
| Response 400 | 音檔格式錯誤或 reference\_text 為空 |
| Response 429 | 該裝置本月評量用量超過上限 |
| Response 503 | Azure Speech 服務異常 |

| // Request |
| :---- |
| POST /api/assessment |
| X-Device-ID: 550e8400-e29b-41d4-a716-446655440000 |
| Content-Type: multipart/form-data |
| audio: \[WAV binary\] |
| reference\_text: It was the best of times. |
| sentence\_id: 550e8400-... |
|   |
| // Response 200 |
| { |
|   "success": true, |
|   "data": { |
|     "overall\_score": 78, |
|     "word\_scores": \[ |
|       { |
|         "word": "best", |
|         "score": 92, |
|         "phonemes": \[ |
|           { "phoneme": "b", "score": 95 }, |
|           { "phoneme": "ɛ", "score": 88 }, |
|           { "phoneme": "s", "score": 94 }, |
|           { "phoneme": "t", "score": 90 } |
|         \] |
|       } |
|     \] |
|   } |
| } |

## **4.6 GET /api/usage  用量查詢**

| 項目 | 說明 |
| :---- | :---- |
| 用途 | App 查詢本裝置本月用量（設定頁顯示用） |
| Request Headers | X-Device-ID: {device\_id} |
| Response 200 | { tts\_chars\_used, translate\_chars\_used, month } |

# **5\. 安全策略**

## **5.1 API Key 保護**

| Key | 存放位置 | 說明 |
| :---- | :---- | :---- |
| Azure TTS API Key | Koyeb 環境變數 AZURE\_TTS\_KEY | 絕對不能出現在程式碼或 Git |
| Azure TTS Region | Koyeb 環境變數 AZURE\_TTS\_REGION | 例如 eastasia / southeastasia |
| Azure Translator Key | Koyeb 環境變數 AZURE\_TRANSLATOR\_KEY | 翻譯服務，與 Speech 可使用同一 Azure 帳號 |
| Azure Speech Key（TTS \+ 評量共用） | Koyeb 環境變數 AZURE\_SPEECH\_KEY | TTS 和評量共用同一個 Key |
| Supabase URL | Koyeb 環境變數 SUPABASE\_URL | 公開資訊，但仍建議放環境變數 |
| Supabase Service Role Key | Koyeb 環境變數 SUPABASE\_SERVICE\_KEY | 超級權限，絕對不能外洩 |

| *SUPABASE\_SERVICE\_KEY 擁有完整資料庫權限，若外洩等同資料庫門戶大開。必須存在 Koyeb 環境變數，絕對不能 commit 到 Git。* |
| :---- |

## **5.2 限流（Rate Limiting）**

每個裝置的月用量上限，超過後回傳 HTTP 429：

| 服務 | 月上限 | 說明 |
| :---- | :---- | :---- |
| TTS | 500,000 字元 | 約 5 本書整本生成，測試階段足夠 |
| 翻譯 | 500,000 字元 | 約 5 本書整本翻譯，在 Google 免費額度內 |

實作方式：每次請求前查詢 Supabase，加總當月 usage\_logs 的 chars\_used，超過上限直接拒絕。

## **5.3 請求驗證**

* 所有請求必須帶 X-Device-ID Header，否則回傳 400

* X-Device-ID 必須是有效的 UUID 格式，否則回傳 400

* X-Device-ID 必須存在於 devices 表（已註冊），否則回傳 401

* TTS 的 text 欄位最長 5,000 字元，超過回傳 400

* 翻譯的 sentences 最多 10 句，每句最長 1,000 字元

## **5.4 Caffeine Cache 快取策略**

為減少 Supabase 查詢次數，使用 Spring Boot 內建的 Caffeine Cache 快取已驗證的 device\_id：

| 快取項目 | 有效期 | 說明 |
| :---- | :---- | :---- |
| device\_id 驗證結果 | 60 分鐘 | 快取後同一裝置 60 分鐘內不需再查 Supabase |
| 月用量統計 | 5 分鐘 | 減少頻繁查詢 usage\_logs 的壓力 |

快取儲存在後端 JVM 記憶體，後端重啟後清空。重啟後第一次請求會多查一次 Supabase，約慢 100ms，用戶感覺不到。

| *未來換 Redis 的改動範圍：build.gradle 換依賴 \+ application.properties 換連線設定 \+ Railway 加一個 Redis 服務。業務邏輯（Controller、Service）完全不用動，因為都用 @Cacheable 抽象層。* |
| :---- |

## **5.5 CORS 設定**

後端只服務 Android App，不需要開放瀏覽器跨域請求。CORS 只允許 App 的請求，拒絕來自瀏覽器的直接呼叫。

# **6\. 錯誤處理**

## **6.1 HTTP 狀態碼規範**

| 狀態碼 | 情境 | 回應格式 |
| :---- | :---- | :---- |
| 200 | 成功 | { success: true, data: {...} } |
| 400 | 請求格式錯誤（缺少欄位、格式不合法） | { success: false, error: "INVALID\_REQUEST", message: "..." } |
| 401 | 裝置 ID 未註冊 | { success: false, error: "UNAUTHORIZED" } |
| 429 | 用量超過月上限 | { success: false, error: "QUOTA\_EXCEEDED", message: "..." } |
| 500 | 後端內部錯誤 | { success: false, error: "INTERNAL\_ERROR" } |
| 503 | 上游服務（TTS / 翻譯）不可用 | { success: false, error: "SERVICE\_UNAVAILABLE" } |

## **6.2 App 端的錯誤處理策略**

| 錯誤碼 | App 行為 |
| :---- | :---- |
| 400 | 記錄 log，不顯示給用戶（屬於程式問題） |
| 401 | 重新呼叫 POST /api/devices 重新註冊，然後重試 |
| 429 | 顯示提示：「本月用量已達上限，音檔／翻譯功能暫停至下月」 |
| 500 | 顯示提示：「服務暫時無法使用，請稍後再試」 |
| 503 | 顯示提示：「服務暫時無法使用，請稍後再試」 |
| 網路錯誤 | 使用本地快取；顯示離線提示 |

# **7\. 部署流程**

## **7.0 事前準備：取得 API Key**

### **Azure Cognitive Services TTS**

1. 登入 https://portal.azure.com

2. 搜尋「Cognitive Services」→ 建立新資源

3. 選擇 Speech service，區域建議選 East Asia 或 Southeast Asia

4. 建立完成後進入資源，在 Keys and Endpoint 取得：

   * KEY 1（AZURE\_TTS\_KEY）

   * Location / Region（AZURE\_TTS\_REGION，例如 eastasia）

### **Azure Translator**

5. 登入 https://portal.azure.com（與 Speech Service 同一帳號）

6. 搜尋「Translator」→ 建立新資源

7. 定價層選「Free F0」（每月 2,000,000 字元免費）

8. 區域建議選 Global 或 East Asia

9. 建立完成後在 Keys and Endpoint 取得：

   * KEY 1（AZURE\_TRANSLATOR\_KEY）

   * Location（AZURE\_TRANSLATOR\_REGION）

| *Azure Translator 免費額度每月 2,000,000 字元，是 Google 的 4 倍。超出後 $10 / 百萬字元，是 Google 的一半。與 Speech Service 使用同一 Azure 帳號，帳單統一管理。* |
| :---- |

## **7.1 Supabase 設定步驟**

10. 前往 https://supabase.com 建立免費帳號

11. 建立新專案，選擇離用戶最近的區域（建議 Southeast Asia）

12. 在 SQL Editor 執行第 2 章的所有建表語法

13. 在 Settings → API 取得以下資訊：

    * Project URL（SUPABASE\_URL）

    * anon public key（給 App 端查詢公開資料用，MVP 不需要）

    * service\_role key（SUPABASE\_SERVICE\_KEY，後端專用）

14. 在 Database → Indexes 確認索引建立成功

## **7.2 Koyeb 設定步驟**

15. 前往 https://koyeb.com 建立免費帳號（不需信用卡）

16. 點「Create Web Service」→ 選「GitHub」→ 選擇後端 repo

17. Builder 選「Buildpack」（Koyeb 自動偵測 Spring Boot，不需 Dockerfile）

18. 在 Environment Variables 頁面設定所有環境變數：

| AZURE\_SPEECH\_KEY=your\_azure\_speech\_key |
| :---- |
| AZURE\_REGION=eastasia |
| AZURE\_TRANSLATOR\_KEY=your\_azure\_translator\_key |
| AZURE\_TRANSLATOR\_REGION=eastasia |
| SUPABASE\_URL=https://xxxxx.supabase.co |
| SUPABASE\_SERVICE\_KEY=eyJhbGci... |
| TTS\_MONTHLY\_LIMIT=500000 |
| TRANSLATE\_MONTHLY\_LIMIT=500000 |
| ASSESSMENT\_MONTHLY\_LIMIT=10000 |
| PORT=8080 |

19. Health Check 設定：Path 填 /api/health

20. 點 Deploy，Koyeb 自動 build 並部署

21. 部署完成後取得公開 URL（格式：{app-name}-{org-name}.koyeb.app）

22. 在 App 的 BuildConfig 設定 API\_BASE\_URL 為此 URL

| *Koyeb 免費方案：1 vCPU、512MB RAM、1GB 流量、不需信用卡、永久有效、商業使用允許。每次 push 到 GitHub 自動重新部署。* |
| :---- |

## **7.3 本地開發設定**

在 src/main/resources/application-local.properties 設定本地環境變數（此檔案加入 .gitignore）：

| \# application-local.properties（不 commit 到 Git） |
| :---- |
| azure.tts.key=your\_azure\_key\_here |
| azure.tts.region=eastasia |
| azure.translator.key=your\_azure\_translator\_key\_here |
| azure.translator.region=eastasia |
| supabase.url=https://xxxxx.supabase.co |
| supabase.service.key=eyJhbGci... |
| quota.tts.monthly=500000 |
| quota.translate.monthly=500000 |

# **8\. Spring Boot 專案結構**

| litspeak-backend/ |
| :---- |
| ├── src/main/java/com/litspeak/ |
| │   ├── LitSpeakApplication.java |
| │   ├── config/ |
| │   │   ├── CorsConfig.java |
| │   │   └── SecurityConfig.java |
| │   ├── controller/ |
| │   │   ├── DeviceController.java      // POST /api/devices |
| │   │   ├── AssessmentController.java  // POST /api/assessment |
| │   │   ├── TtsController.java         // POST /api/tts |
| │   │   ├── TranslateController.java   // POST /api/translate |
| │   │   ├── ContentController.java     // GET /api/content-library |
| │   │   └── UsageController.java       // GET /api/usage |
| │   ├── service/ |
| │   │   ├── DeviceService.java |
| │   │   ├── TtsService.java            // Azure TTS REST API |
| │   │   ├── CacheService.java          // Caffeine Cache 管理 |
| │   │   ├── TranslateService.java      // Azure Translator REST API |
| │   │   ├── AssessmentService.java     // Azure Speech Pronunciation Assessment |
| │   │   ├── ContentService.java |
| │   │   └── QuotaService.java          // 用量查詢與限流 |
| │   ├── middleware/ |
| │   │   └── DeviceIdInterceptor.java   // 驗證 X-Device-ID |
| │   ├── model/ |
| │   │   ├── TtsRequest.java |
| │   │   ├── TranslateRequest.java |
| │   │   └── ApiResponse.java |
| │   └── supabase/ |
| │       └── SupabaseClient.java        // HTTP client for Supabase REST API |
| ├── src/main/resources/ |
| │   ├── application.properties |
| │   └── application-local.properties   // gitignored |
| ├── .gitignore |
| ├── build.gradle |
| ├── system.properties              // 指定 Java 版本給 Koyeb |
| └── .gitignore |

# **9\. 給 Claude Code 的 Prompt**

| *將以下內容完整貼給 Claude Code 執行，可直接生成可運行的後端專案。* |
| :---- |

## **9.1 第一步：建立基礎專案**

| 建立一個 Spring Boot 3.x (Java 17\) 後端專案，名稱為 litspeak-backend。 |
| :---- |
|   |
| 專案功能：LitSpeak App 的後端 API，負責中轉 TTS 和翻譯請求，記錄用量到 Supabase。 |
|   |
| 建立以下 5 個 API 端點： |
| 1\. POST /api/devices     \- 裝置註冊 |
| 2\. POST /api/tts         \- 文字轉語音（中轉到 Edge TTS） |
| 3\. POST /api/translate   \- 翻譯（中轉到 Google Translate） |
| 4\. GET  /api/content-library \- 官方內容庫（先回傳空陣列） |
| 5\. GET  /api/usage       \- 查詢裝置本月用量 |

## **9.2 第二步：詳細規格**

| 所有請求規格： |
| :---- |
|   |
| 共用 Header：X-Device-ID（UUID 格式） |
| Interceptor 驗證：所有請求都要有 X-Device-ID，格式不對回傳 400， |
|                裝置未在 Supabase 的 devices 表回傳 401。 |
|   |
| POST /api/devices |
|   Body: { device\_id: string, platform: string, app\_version: string } |
|   行為: Upsert 到 Supabase devices 表，回傳 { success: true, data: {...} } |
|   |
| POST /api/tts |
|   Body: { text: string (max 5000), voice\_code: string, speed: int (-40/-20/0/20) } |
|   行為: 呼叫 Azure Cognitive Services TTS REST API，回傳 audio/mpeg |
|        Azure TTS endpoint: https://{AZURE\_TTS\_REGION}.tts.speech.microsoft.com/cognitiveservices/v1 |
|        Header: Ocp-Apim-Subscription-Key: {AZURE\_TTS\_KEY} |
|                Content-Type: application/ssml+xml |
|                X-Microsoft-OutputFormat: audio-16khz-128kbitrate-mono-mp3 |
|        Body: SSML 格式，voice\_code 對應 Azure 聲音名稱（如 en-US-JennyNeural） |
|        speed 對應 SSML rate 屬性：-40%/-20%/0%/+20% |
|        成功後寫 usage\_logs (service=tts, chars\_used=text.length) |
|        呼叫前先查本月用量，超過 QUOTA\_TTS\_MONTHLY 回傳 429 |
|   |
| POST /api/translate |
|   Body: { sentences: string\[\] (max 10), target\_lang: string } |
|   行為: 呼叫 Azure Translator API，回傳 { translations: string\[\] } |
|        Azure endpoint: https://api.cognitive.microsofttranslator.com/translate?api-version=3.0\&to={target\_lang} |
|        Header: Ocp-Apim-Subscription-Key \+ Ocp-Apim-Subscription-Region |
|        成功後寫 usage\_logs (service=translate, chars\_used=總字元數) |
|        呼叫前先查本月用量，超過 QUOTA\_TRANSLATE\_MONTHLY 回傳 429 |
|   |
| POST /api/assessment |
|   Body: multipart/form-data { audio: WAV file, reference\_text: string, sentence\_id: string } |
|   行為: 接收錄音檔 → 送 Azure Speech Pronunciation Assessment API → 解析結果 → 回傳分數 |
|        Azure endpoint: https://{AZURE\_REGION}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1 |
|        Header: Ocp-Apim-Subscription-Key: {AZURE\_SPEECH\_KEY} |
|                Pronunciation-Assessment: base64({ReferenceText, GradingSystem:HundredMark, Granularity:Phoneme}) |
|        後端不儲存錄音，處理完立即丟棄 |
|        回傳: { overall\_score, word\_scores: \[{word, score, phonemes: \[{phoneme, score}\]}\] } |
|   |
| GET /api/content-library |
|   Query: type (optional) |
|   行為: 查 Supabase official\_content 表，只回傳 is\_published=true 的資料 |
|        MVP 先建好邏輯，資料為空時回傳 { items: \[\] } |
|   |
| GET /api/usage |
|   行為: 查 usage\_logs 統計本裝置本月 tts 和 translate 的總 chars\_used |

## **9.3 第三步：Supabase 整合**

| Supabase 整合方式：使用 OkHttp 直接呼叫 Supabase REST API（不用 SDK）。 |
| :---- |
|   |
| Supabase REST API 格式： |
|   Base URL: {SUPABASE\_URL}/rest/v1 |
|   Header: apikey: {SUPABASE\_SERVICE\_KEY} |
|           Authorization: Bearer {SUPABASE\_SERVICE\_KEY} |
|           Content-Type: application/json |
|           Prefer: return=minimal  (for INSERT) |
|   |
| 建立 SupabaseClient.java，包含以下方法： |
|   \- upsertDevice(deviceId, platform, appVersion) |
|   \- deviceExists(deviceId): boolean |
|   \- insertUsageLog(deviceId, service, contentType, charsUsed, status) |
|   \- getMonthlyUsage(deviceId, service, yearMonth): int |
|   \- getPublishedContent(type): List\<Map\> |

## **9.3b 第三步補充：Caffeine Cache 設定**

| 在 build.gradle 加入 Caffeine 依賴： |
| :---- |
|   implementation 'com.github.ben-manes.caffeine:caffeine' |
|   implementation 'org.springframework.boot:spring-boot-starter-cache' |
|   |
| 在 LitSpeakApplication.java 加上 @EnableCaching |
|   |
| 建立 CacheConfig.java： |
|   @Bean CacheManager cacheManager() { |
|     return new CaffeineCacheManager( |
|       CaffeineSpec.parse(maximumSize=10000,expireAfterWrite=60m)  // device\_id 快取 60 分鐘 |
|     ); |
|   } |
|   |
| 在 DeviceService.deviceExists() 加上 @Cacheable(value=deviceIds, key=\#deviceId) |
| 在 QuotaService.getMonthlyUsage() 加上 @Cacheable(value=quotas, key=\#deviceId+\#service, condition=true) |
|   \-- 月用量快取 5 分鐘（避免頻繁查 Supabase） |
|   |
| 未來換 Redis 只需： |
|   1\. build.gradle: 換成 spring-boot-starter-data-redis |
|   2\. application.properties: 加 spring.redis.host/port |
|   3\. 業務邏輯不動 |

## **9.4 第四步：環境變數**

| 從環境變數讀取所有設定（不寫死在程式碼）： |
| :---- |
|   |
| application.properties： |
|   azure.tts.key=${AZURE\_TTS\_KEY} |
|   azure.tts.region=${AZURE\_TTS\_REGION} |
|   azure.translator.key=${AZURE\_TRANSLATOR\_KEY} |
|   azure.translator.region=${AZURE\_TRANSLATOR\_REGION} |
|   supabase.url=${SUPABASE\_URL} |
|   supabase.service.key=${SUPABASE\_SERVICE\_KEY} |
|   quota.tts.monthly=${TTS\_MONTHLY\_LIMIT:500000} |
|   quota.translate.monthly=${TRANSLATE\_MONTHLY\_LIMIT:500000} |
|   server.port=${PORT:8080} |
|   |
| 請同時提供 application-local.properties.example 範例檔（不含實際 key）。 |
| 在 .gitignore 加入 application-local.properties。 |

## **9.5 第五步：回應格式與錯誤處理**

| 統一回應格式 ApiResponse\<T\>： |
| :---- |
|   { success: boolean, data: T, error: string, message: string } |
|   |
| GlobalExceptionHandler 處理： |
|   \- MethodArgumentNotValidException \-\> 400 |
|   \- DeviceNotFoundException \-\> 401 |
|   \- QuotaExceededException \-\> 429 |
|   \- TtsServiceException \-\> 503 |
|   \- TranslateServiceException \-\> 503 |
|   \- Exception \-\> 500 |
|   |
| 所有 exception 都要 log（使用 SLF4J），包含 device\_id 和錯誤原因。 |

## **9.6 第六步：部署設定**

| 建立 system.properties（告訴 Koyeb 使用 Java 17）： |
| :---- |
|   java.runtime.version=17 |
|   |
| 建立 GET /api/health endpoint，回傳 { status: ok, timestamp: ... } |
| 用於 Koyeb 的 Health Check 設定。 |
|   |
| build.gradle 使用 Gradle 8.x，Spring Boot 3.x，Java 17。 |
| 加入以下依賴： |
|   \- spring-boot-starter-web |
|   \- spring-boot-starter-validation |
|   \- spring-boot-starter-cache |
|   \- com.github.ben-manes.caffeine:caffeine |
|   \- okhttp3 (4.x) |
|   \- jackson-databind |
|   |
| 不需要 railway.toml，Koyeb 在控制台設定即可。 |
| 不需要 Dockerfile，Koyeb Buildpack 自動偵測 Spring Boot。 |

# **10\. 開發順序與驗收標準**

| 步驟 | 任務 | 驗收標準 |
| :---- | :---- | :---- |
| 1 | 建立專案，設定環境變數，部署到 Railway | GET /api/health 回傳 200 |
| 2 | 實作 POST /api/devices \+ Supabase 連線 | 裝置 ID 成功寫入 Supabase devices 表 |
| 3 | 實作 POST /api/tts | App 呼叫後收到 MP3 並成功播放 |
| 4 | 實作 POST /api/translate | App 呼叫後收到正確 Azure 翻譯 |
| 4b | 實作 POST /api/assessment | App 送錄音後收到評量分數 JSON |
| 5 | 實作用量記錄 \+ 限流 | usage\_logs 有記錄，超量後回傳 429 |
| 6 | 實作 POST /api/assessment | App 送錄音後收到 Azure 評量結果 |
| 7 | 實作 GET /api/content-library \+ GET /api/usage | 格式正確，用量統計正確 |
| 8 | 部署到 Koyeb | GET /api/health 在 Koyeb 公開 URL 回傳 200 |

—— 文件結束  後端規劃書 v1.0 ——