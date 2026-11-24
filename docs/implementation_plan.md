# Implementation Plan
## Disney Pin Quick-Valuator & Trade Assistant (Android)

**Version:** 2.0  
**Target Codebase:** `/android` (Native Android — Kotlin 2.0, Jetpack Compose, Material 3, SQLite)  
**Design Goal:** Zero-ambiguity, step-by-step specification engineered for reliable implementation by a smaller coding model.

---

## Revision History

| Version | Date | Summary of Changes |
| :--- | :--- | :--- |
| **1.0** | 2026-11-23 | Initial 20-week enterprise cloud roadmap (React Native/React, Node.js/FastAPI, PostgreSQL/MongoDB/Qdrant, PyTorch/OpenCLIP, Scrapy crawlers, AWS ECS). |
| **2.0** | 2026-11-23 | **Complete realignment to the existing Android codebase** (`android/`):<br>• Replaced cloud microservices with a 5-phase native Android plan using the existing bundled `disney_pin_database.sqlite` + Gemini REST API (`OkHttp` + `org.json`).<br>• Added exact codebase audit of existing files and concrete bugs to fix.<br>• Specified exact SQLite `PRAGMA user_version` migrations, generated-column guardrails, pure-Kotlin visual fingerprinting (`hsv-dhash-v1`), Gemini Pass 1 & Pass 2 JSON contracts, and unit test checkpoints for each phase. |

---

## 1. Existing Codebase Audit & Bugs to Fix

Before writing new code, review what already exists in `android/app/src/main/java/com/disneypin/valuator/`:

| Existing File | Current Status | Action in v2.0 |
| :--- | :--- | :--- |
| `assets/disney_pin_database.sqlite` | 595 seeded pins, FTS5 (`pins_fts`), `price_history` (595 baseline rows), `board_scans` (1 row), `scan_detections` (0 rows), `pin_embeddings` (0 rows), plus triggers `pins_ai/ad/au` and `pins_price_audit`. | **KEEP** asset as-is. Apply additive schema migrations at runtime via `PinDatabaseHelper`. |
| `AndroidManifest.xml` | Missing `INTERNET` and `ACCESS_NETWORK_STATE` permissions. | **FIX:** Add both permissions. |
| `app/build.gradle.kts` | Compose + Material3 only; no HTTP client, no EXIF helper, no `buildConfig`. | **MODIFY:** Add OkHttp, ExifInterface, `buildConfig = true`, and `testImplementation("org.json:json:20240303")`. |
| `data/db/PinDatabaseHelper.kt` | Copies asset DB on first launch, creates `portfolio_items` & `trade_wishlist`, does NOT enable foreign keys or schema migrations. | **MODIFY:** Enable `PRAGMA foreign_keys = ON`; add idempotent `PRAGMA user_version = 2` migration adding new columns & `pending_scans`. |
| `data/db/PinRepository.kt` | Read-only for `pins`. **BUG at line 179:** `getPriceHistory()` queries non-existent columns (`id, old_low_usd, old_high_usd, changed_at`) and silently swallows the `SQLiteException`! | **FIX & EXTEND:** Fix `getPriceHistory()` SQL; add `insertLearnedPin()`, `updatePinValuation()`, `upsertPinEmbedding()`, `getAllPinEmbeddings()`, `saveBoardScan()`, `getRecentBoardScans()`, `getScanDetections()`, and `pending_scans` CRUD. |
| `data/model/PinModels.kt` | Models for catalog, portfolio, trade matching, manual scrapper check. | **EXTEND:** Add scan, bounding box, Gemini DTO, embedding, and citation models. |
| `domain/EvaluationEngine.kt` | Pure-Kotlin scrapper scoring, condition grading, trade parity matching (all 5 unit tests pass). | **KEEP** intact. Access optionally from `PinDetailScreen`. |
| `ui/screens/ScannerScreen.kt` | **STUB BUG:** `frontPhotoUri`/`backPhotoUri` are local state variables that are never rendered or processed. Screen is a 10-field manual form. | **RENAME / MOVE** to `ManualAppraisalScreen.kt` (optional secondary route from Pin Detail). Build new `ScanHomeScreen.kt` and `ScanResultScreen.kt`. |
| `MainActivity.kt` | 4 bottom tabs starting on `Catalog`. | **MODIFY:** 3 bottom tabs (`Scan`, `Catalog`, `My Pins`) starting on `Scan` + TopAppBar Settings icon. |
| `CatalogScreen.kt`, `PinDetailScreen.kt`, `PortfolioScreen.kt`, `TradesScreen.kt` | Working UIs; no thumbnails, no online refresh, no source filter. | **ENHANCE:** Add thumbnails/source filter to Catalog; add refresh/citations/edit to Detail; wrap Portfolio + Trades inside `MyPinsScreen.kt`. |

---

## 2. Target Architecture (Local-First + 1–2 Call Gemini Hybrid)

```
 [ User: Take Photo / Upload 1..10 Photos ]
                     |
                     v
        [ ImageUtils (EXIF + <=1600px) ]
                     |
        +------------+------------+
        | (Offline?)              | (Online + API Key, Sequential Pacer >=4s)
        v                         v
 [ Queue to pending_scans ]  [ GeminiApiClient: Pass 1 (1 Call / Photo) ]
 [ + Local FTS / Cache    ]  (JSON, no search, maxOutputTokens=4096 ->
                              box_2d + attrs + est_low/high_usd + needs_web_verification)
                                  |
                                  v
                     [ Crop each pin box -> Bitmap ]
                                  |
                                  v
            [ LocalPinMatcher (Pass 0 Visual + FTS Metadata) ]
                 |                     |                         |
        (Local Hit & Fresh)   (Common/Confident Miss)   (High-Value >=$25 OR
                 |             needs_web_verification=F  Uncertain <0.70 OR Stale)
                 v                     v                         |
      [ LOCAL_DB / VISUAL ]   [ GEMINI_ESTIMATE ]                v
      [ 0 extra API calls ]   [ Save to SQLite  ]   [ Select Top <=4 Pins ]
                              [ 0 extra calls   ]   [ AT MOST 1 Pass 2 Call ]
                                                    (192px crops + google_search
                                                     + maxOutputTokens=2048)
                                                         |
                                                    (Success? / Circuit Breaker)
                                                         v
                                        [ Knowledge Extension Write-Back ]
                                        - INSERT new pin (GEM-XXXX) OR UPDATE price
                                        - SQLite triggers update pins_fts & price_history
                                        - Save crop JPEG to filesDir/pin_crops/
                                        - Store hsv-dhash-v1 BLOB in pin_embeddings
                                        - On 429/503/Timeout: keep Pass 1 Gemini Est.!
                                                         |
                                                         v
                     [ Log to board_scans & scan_detections ]
                                  |
                                  v
       [ ScanResultScreen: Annotated Photo Pills + Value-Ranked Trade List ]
```

---

## 3. Dependencies & Build Configuration

### 3.1 `android/app/build.gradle.kts`
Enable `buildConfig` and read an optional developer key from `local.properties` (never committed):

```kotlin
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultGeminiKey = localProps.getProperty("GEMINI_API_KEY", "")

android {
    defaultConfig {
        buildConfigField("String", "DEFAULT_GEMINI_API_KEY", "\"$defaultGeminiKey\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Existing dependencies remain unchanged; add:
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("org.json:json:20240303")
}
```

> **Why `OkHttp` + `org.json` instead of an AI SDK or Retrofit/KSP?**  
> Zero annotation-processor/KSP compatibility issues, tiny APK footprint, full control over Gemini REST JSON fields (`thinkingConfig`, `tools: [{"google_search": {}}]`, `groundingMetadata`), and trivial to unit-test with pure JVM JUnit tests.

### 3.2 `android/app/src/main/AndroidManifest.xml`
Add inside `<manifest>` above `<application>`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 4. Exact SQLite Schema & Migration Rules

### 4.1 Critical Guardrails on Existing `pins` Table
> [!CAUTION]
> Look closely at `pins` in `disney_pin_database.sqlite` before writing any SQL:
> 1. **NEVER include `est_market_value_usd` or `price_tier` in `INSERT` or `UPDATE` statements.** Both are `GENERATED ALWAYS AS (...) STORED` columns; writing to them throws `SQLiteException`.
> 2. **ALWAYS sanitize enum columns** before writing to satisfy `CHECK` constraints:
>    - `rarity` MUST be one of: `'Common'`, `'Chaser / Completer'`, `'Super Chaser'`
>    - `price_confidence` MUST be one of: `'Verified'`, `'Peer Est.'`, `'Low Conf'`
>    - `scrapper_risk` MUST be one of: `'Low'`, `'Medium'`, `'High'`
> 3. **ALWAYS enforce `market_high_usd >= market_low_usd >= 0.0`** before writing (`CHECK` constraints).
> 4. **Existing triggers fire automatically:**
>    - `pins_ai` / `pins_au` / `pins_ad` automatically keep `pins_fts` in sync.
>    - `pins_price_audit` automatically inserts a row into `price_history` on `UPDATE OF market_low_usd, market_high_usd ON pins`.

### 4.2 Idempotent Schema Migration (`PRAGMA user_version = 2`)
In `PinDatabaseHelper.openOrCopyDatabase()`:
1. Call `db.execSQL("PRAGMA foreign_keys = ON")` immediately after opening.
2. Call `ensureAppTables(db)` (existing `portfolio_items`, `trade_wishlist`, plus new `pending_scans`).
3. Check `PRAGMA user_version`. If `< 2`, run additive migrations guarded by a helper `addColumnIfMissing(db, table, column, definition)` using `PRAGMA table_info(table)`:

```sql
-- Additive columns on pins
ALTER TABLE pins ADD COLUMN data_source TEXT NOT NULL DEFAULT 'Seeded';
ALTER TABLE pins ADD COLUMN reference_image_path TEXT NOT NULL DEFAULT '';
ALTER TABLE pins ADD COLUMN valuation_sources TEXT NOT NULL DEFAULT '';

-- Additive columns on board_scans
ALTER TABLE board_scans ADD COLUMN image_path TEXT NOT NULL DEFAULT '';
ALTER TABLE board_scans ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED';

-- Additive columns on scan_detections
ALTER TABLE scan_detections ADD COLUMN box_json TEXT NOT NULL DEFAULT '';
ALTER TABLE scan_detections ADD COLUMN match_source TEXT NOT NULL DEFAULT 'LOCAL_DB';
ALTER TABLE scan_detections ADD COLUMN detected_label TEXT NOT NULL DEFAULT '';
ALTER TABLE scan_detections ADD COLUMN market_low_usd REAL NOT NULL DEFAULT 0.0;
ALTER TABLE scan_detections ADD COLUMN market_high_usd REAL NOT NULL DEFAULT 0.0;

-- New table for offline queued photos
CREATE TABLE IF NOT EXISTS pending_scans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    image_path TEXT NOT NULL,
    queued_at TEXT NOT NULL DEFAULT (DATETIME('now')),
    status TEXT NOT NULL DEFAULT 'PENDING'
);

PRAGMA user_version = 2;
```

### 4.3 Bug Fix: `PinRepository.getPriceHistory()`
Replace the broken SQL at `PinRepository.kt:179` with:
```sql
SELECT history_id, pin_id, market_low_usd, market_high_usd, est_market_value_usd, observed_date, source
FROM price_history
WHERE pin_id = ?
ORDER BY observed_date DESC, history_id DESC
LIMIT 20
```

### 4.4 Write Operations in `PinRepository.kt`
* `generateNextGeminiPinId(db): String`: Query `SELECT MAX(CAST(SUBSTR(pin_id, 5) AS INTEGER)) FROM pins WHERE pin_id LIKE 'GEM-%'` and return `"GEM-%04d".format(maxNum + 1)`.
* `upsertLearnedPin(candidate: LearnedPinInput): Pin`:
  1. Look up existing pin by case-insensitive exact match on `(pin_name, collection, sub_set)`.
  2. If found: call `updatePinValuation(existing.pinId, ...)` and return updated `Pin`.
  3. If not found: generate `GEM-XXXX`, insert into `pins` (explicitly listing non-generated columns only), also insert an initial row into `price_history` (`source = 'Gemini Search'`), and return `getPinById(newId)!!`.
* `updatePinValuation(pinId, lowUsd, highUsd, confidence, scrapperRisk, sourcesJson, referenceImagePath?)`: Updates `pins` (firing `pins_price_audit` trigger automatically when low/high change).
* `upsertPinEmbedding(pinId, imagePath, modelName = "hsv-dhash-v1", embeddingBytes)`: Replaces any existing embedding for `(pinId, modelName)` and updates `pins.reference_image_path`.
* `saveBoardScan(imagePath, location, detections): Long`: Inserts into `board_scans` and all rows into `scan_detections` inside a single SQLite transaction (`beginTransaction()` / `setTransactionSuccessful()`).

---

## 5. Pure-Kotlin Visual Fingerprinting & Local Matching

### 5.1 Visual Fingerprint (`domain/VisualFingerprint.kt`)
No OpenCV or PyTorch is required. Implement a deterministic, fast, pure-Kotlin 200-byte descriptor (`model_name = "hsv-dhash-v1"`) on any cropped pin `Bitmap`:
1. **64-bit dHash (8 bytes):** Resize crop to `9x8` grayscale; for each row `y in 0..7` and col `x in 0..7`, set bit `(y * 8 + x)` if `luma(x, y) > luma(x + 1, y)`.
2. **48-float HSV Color Histogram (192 bytes):** Resize crop to `64x64`, ignore outer 12% margin (reduces board/hand background bleed), convert each pixel to HSV via `Color.colorToHSV()`, bin into:
   - Hue: 24 bins (`0..360°`, weighted by saturation `>= 0.15`)
   - Saturation: 12 bins (`0..1`)
   - Value: 12 bins (`0..1`)
   L2-normalize the 48-float vector.
3. **Serialization:** Pack 8-byte `Long` (big-endian) + 48 IEEE-754 `Float`s = **200-byte `ByteArray`** stored in `pin_embeddings.embedding_blob`.
4. **Similarity Score (`0.0..1.0`):**
   * `dHashSim = 1.0 - (java.lang.Long.bitCount(hashA xor hashB) / 64.0)`
   * `hsvSim = dotProduct(histA, histB).coerceIn(0f, 1f)`
   * `combined = 0.40 * dHashSim + 0.60 * hsvSim`
   * **Local Visual Match Threshold:** `combined >= 0.84` (and gap over 2nd-best match `>= 0.04` to avoid false positives).

### 5.2 Local Catalog Matcher (`domain/LocalPinMatcher.kt`)
Given a Pass 1 detection (`DetectedPinCandidate`) and its cropped `Bitmap`:
1. **Pass 0 (Visual Cache):** If `pin_embeddings` is non-empty, compute `hsv-dhash-v1` on the crop and score against cached embeddings. If best score `>= 0.84`, look up that `pin_id`.
2. **FTS5 + Weighted Attribute Match:**
   * Build FTS5 query from the strongest non-empty tokens of `character` / `pin_name` (and fallback `sub_set_guess`), fetching up to 25 candidates from `pins`.
   * Score each candidate `p` in `0.0..1.0`:
     - **Name/Character overlap (0.50):** Normalized token Jaccard + substring bonus between `(detected.pinName + " " + detected.character)` and `p.pinName`.
     - **Collection/Sub-Set overlap (0.30):** Token overlap between `(detected.collectionGuess + " " + detected.subSetGuess)` and `(p.collection + " " + p.subSet)`.
     - **Visual Tags & Resort overlap (0.20):** Token overlap between `(detected.visualTags + " " + detected.resortGuess)` and `(p.visualTags + " " + p.resort)`.
   * **Local Metadata Match Threshold:** `bestScore >= 0.72`.
3. **Freshness Check:**
   * Parse `matchedPin.priceUpdated` (`YYYY-MM-DD`).
   * If `daysBetween(priceUpdated, today) <= staleThresholdDays` (default `60`): return `MatchOutcome.LocalFresh(matchedPin, score)`.
   * Else: return `MatchOutcome.LocalStale(matchedPin, score)` (eligible for Pass 2 price refresh).
   * If no match meets threshold: return `MatchOutcome.Unmatched`.

---

## 6. Exact Gemini REST API Contract (`data/remote/GeminiApiClient.kt`)

**Base URL:**
`https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
* Pass API key via HTTP header `x-goog-api-key: <apiKey>` (preferred over URL query string so keys don't leak into logs).
* Default `model`: `"gemini-2.5-flash"` (configurable in Settings).
* **Separate OkHttp Timeouts:**
  * Pass 1 (`detectPinsInPhoto`): `connectTimeout = 15s`, `readTimeout = 45s`, `writeTimeout = 30s`.
  * Pass 2 (`researchPinValuations`): `connectTimeout = 15s`, `readTimeout = 60s`, `writeTimeout = 30s`.
* **Global Request Pacer (`Mutex` + timestamp):** Track `lastRequestCompletedAtMs` inside `GeminiApiClient`. Before sending any request, enforce a minimum spacing of **`4_000 ms`** since the previous request finished (unless it is the first call). This prevents burst RPM spikes during Pass 1 → Pass 2 transitions and multi-photo batches.
* **Strict Retry & Error Policy (`executeWithPacingAndRetry`):**
  * **NEVER retry** `SocketTimeoutException` / `InterruptedIOException` (fail fast and fall back to Pass 1 estimates instead of hanging 3×90s).
  * **NEVER retry** HTTP `400`, `401`, `403`, or `404` (on `404`, return `"Model '$model' not found — check Settings"`).
  * **HTTP `429` or `503`:** Perform **at most 1 retry** (`maxRetries = 1`). Read the `Retry-After` response header (in seconds) if present and wait `retryAfterSec.coerceIn(5, 25) * 1000L`; otherwise wait **`10_000 ms`** (`10s`).
  * Throw a typed `GeminiRateLimitException(code, message)` when `429` or `503` still fails after 1 retry so `PinScanOrchestrator` can trip its **batch circuit breaker** immediately.
* **Thinking Config Helper:** Only attach `"thinkingConfig": { "thinkingBudget": 0 }` when `model.contains("2.5")`. For Gemini 3.x+ models (e.g., `gemini-3.8-flash`), omit `thinkingBudget` or use `"thinkingConfig": { "thinkingLevel": "minimal" }` if supported, and ALWAYS set `maxOutputTokens` to prevent 13K+ token spikes.

### 6.1 Pass 1 — Fast Single-Call Board Detection + Initial Valuation
* **Tools:** NONE (no web search — maximizes speed and bounding-box accuracy).
* **Generation Config:**
  ```json
  {
    "temperature": 0.1,
    "maxOutputTokens": 4096,
    "responseMimeType": "application/json",
    "thinkingConfig": { "thinkingBudget": 0 }
  }
  ```
* **Request `contents`:** `[ { "parts": [ { "inline_data": { "mime_type": "image/jpeg", "data": "<BASE64_1600PX_JPEG>" } }, { "text": "<PASS_1_PROMPT>" } ] } ]`
* **Prompt Instructions (`PASS_1_PROMPT`):**
  > You are a Disneyland / Walt Disney World collectible pin expert. Detect EVERY Disney trading pin visible in this image (up to 25 pins) AND provide an immediate realistic USD secondary-market estimate for each pin. Return ONLY valid JSON matching:
  > `{ "pins": [ { "box_2d": [ymin, xmin, ymax, xmax], "pin_name": "short character or subject name", "character": "main Disney character if any", "collection_guess": "e.g. Hidden Disney, Cast Hidden Disney, Open Edition, Limited Edition", "sub_set_guess": "e.g. Ear Headbands, Ear Hats, Disneyland D, Film Anniversaries, Cupcakes, Sundaes", "resort_guess": "DLR or WDW or Unknown", "rarity_hint": "Common | Chaser / Completer | Super Chaser", "est_low_usd": 10.0, "est_high_usd": 16.0, "needs_web_verification": false, "visual_tags": "comma-separated visual traits", "has_hidden_mickey": true, "confidence": 0.85, "scrapper_risk_hint": "Low | Medium | High", "scrapper_notes": "brief visual note if defects visible, else empty" } ] }`
  > Rules: `box_2d` coordinates MUST be integers normalized to `0..1000` (`[ymin, xmin, ymax, xmax]`). Do not merge adjacent pins into one box. Set `needs_web_verification: true` ONLY if the pin appears rare, Limited Edition, Chaser/Super Chaser, likely worth >= $25 USD, OR your identification confidence is < 0.70. Set `needs_web_verification: false` for standard/common open-edition or lanyard pins.

### 6.2 Pass 2 — Selective, Capped Search-Grounded Valuation (Max 1 Call / Board)
* **Triggered ONLY when:** `settings.autoVerifyOnline == true` AND there are pins that are (a) `Unmatched` with `needsWebVerification == true` (or `estHighUsd >= 25.0`, `rarityHint != "Common"`, or `confidence < 0.70`), OR (b) `LocalStale` (`> stalePriceDays`), OR (c) explicit user tap on **"Verify/Refresh Online"**.
* **Hard Cap:** Select **at most 4 pins per board** for automatic Pass 2 (sorted by `estHighUsd` descending, then `confidence` ascending) and send them in **1 single batched call**. Do NOT loop through multiple Pass 2 chunks automatically!
* **Compact Crop Size:** Decode Pass 2 crops at **`maxEdgePx = 192`**, JPEG quality `75` to keep input tokens small and server latency low.
* **Tools:** `"tools": [{ "google_search": {} }]`
* **Generation Config:** `{ "temperature": 0.2, "maxOutputTokens": 2048 }` — **DO NOT set `responseMimeType: "application/json"` when `google_search` tool is enabled**.
* **Prompt Instructions (`PASS_2_PROMPT`):**
  > Use Google Search to quickly verify each numbered Disney pin crop and its current USD secondary market value (eBay sold listings, Mercari, PinPics). Keep search concise and return ONLY a single compact JSON object (no prose before or after):
  > `{ "valuations": [ { "temp_id": 0, "pin_name": "Official or commonly traded pin name", "collection": "Series or collection name", "sub_set": "Sub-set name or empty string", "resort": "DLR | WDW | DLR/WDW | Other", "year_wave": "Year or wave e.g. 2025 Wave A", "rarity": "Common | Chaser / Completer | Super Chaser", "market_low_usd": 14.0, "market_high_usd": 22.0, "price_confidence": "Verified | Peer Est. | Low Conf", "scrapper_risk": "Low | Medium | High", "visual_tags": "concise comma-separated visual descriptors", "notes": "1-sentence valuation & edition summary" } ] }`
* **Defensive Response Parsing:**
  1. Concatenate all `candidates[0].content.parts[].text`.
  2. Strip markdown fences (` ```json ... ``` `) and extract the JSON object (or array wrapped into `{"valuations": [...]}`).
  3. Extract citation sources from `candidates[0].groundingMetadata.groundingChunks[]` (`chunk.web.uri` + `chunk.web.title`), deduplicate, and store up to 5 `{title, url}` objects as JSON in `pins.valuation_sources`.
  4. Sanitize every numeric & enum field (`marketLowUsd >= 0`, `marketHighUsd = max(low, high)`, map invalid `rarity`/`priceConfidence`/`scrapperRisk` to safe defaults).

---

## 7. Phased Implementation Roadmap

> **Note:** Phases 1–5 (v2.0) are already implemented as of commit `c876d3a`. **Phase 6 (v2.1 Delta)** below contains the exact incremental changes to implement next.

### Phases 1–5 (Completed in v2.0 — Reference Only)
* **Phase 1:** Gradle/Manifest setup, `SettingsRepository`, `PinDatabaseHelper` (`user_version = 2` + FTS4 migration in `8391d34`), `PinRepository` write layer & `price_history` fix.
* **Phase 2:** `ImageUtils`, `VisualFingerprint` (`hsv-dhash-v1`), `LocalPinMatcher`.
* **Phase 3:** `GeminiApiClient`, `PinScanOrchestrator`.
* **Phase 4:** `ScanHomeScreen`, `ScanResultScreen`, `SettingsDialog`, `EditPinDialog`, `MyPinsScreen`, `ManualAppraisalScreen`, 3-tab `MainActivity`.
* **Phase 5:** Initial unit tests (`EvaluationEngineTest`, `LocalPinMatcherTest`, `VisualFingerprintTest`, `GeminiParserTest`, `PinRepositorySanitizationTest`) and debug APK build.

---

### Phase 6 (v2.1 Delta): API Efficiency, Smart Batching & Rate-Limit Protection

Execute these exact edits on top of the current `main` branch (`c876d3a`):

#### Step 6.1 — Models & Settings (`PinModels.kt`, `SettingsRepository.kt`, `SettingsDialog.kt`)
1. **`PinModels.kt`:**
   * Add `val autoVerifyOnline: Boolean = true` to `AppSettings`.
   * Add three fields to `DetectedPinCandidate` (with safe defaults so existing tests still compile):
     ```kotlin
     val estLowUsd: Double = 10.0,
     val estHighUsd: Double = 16.0,
     val needsWebVerification: Boolean = false
     ```
   * Add `GEMINI_ESTIMATE("Gemini Est.")` to `MatchSource` enum (between `LOCAL_DB` and `GEMINI_SEARCH`).
2. **`SettingsRepository.kt`:**
   * Persist `KEY_AUTO_VERIFY_ONLINE = "auto_verify_online"` (default `true`) in `loadSettings()` and `saveSettings(...)`.
3. **`SettingsDialog.kt` & `PinValuatorViewModel.kt`:**
   * Add a `Switch` row in `SettingsDialog`: **"Auto-verify top high-value pins online (max 1 search call/board)"** bound to `autoVerifyOnline`.
   * Update `onSave` callback signature to pass `autoVerifyOnline: Boolean`.

#### Step 6.2 — Rate-Limit-Safe Client (`GeminiApiClient.kt`)
1. Add typed exception:
   ```kotlin
   class GeminiRateLimitException(val httpCode: Int, message: String) : IOException(message)
   ```
2. Add a global request pacer inside `GeminiApiClient`:
   * `private val paceMutex = Mutex()`
   * `private var lastRequestEndMs = 0L`
   * `private val minRequestIntervalMs = 4_000L`
   * Before every HTTP call, acquire `paceMutex`, delay if `System.currentTimeMillis() - lastRequestEndMs < minRequestIntervalMs`, execute the call, and update `lastRequestEndMs = System.currentTimeMillis()` in a `finally` block.
3. Split OkHttp timeouts:
   * Pass 1 client: `readTimeout(45, TimeUnit.SECONDS)`
   * Pass 2 client: `readTimeout(60, TimeUnit.SECONDS)`
4. Update `buildThinkingConfig(model)` helper:
   * Only add `"thinkingConfig": { "thinkingBudget": 0 }` when `model.contains("2.5")`.
   * Always add `"maxOutputTokens": 4096` to Pass 1 and `"maxOutputTokens": 2048` to Pass 2.
5. Update `PASS_1_PROMPT` and `GeminiResponseParser.parsePass1Response()`:
   * Add `est_low_usd`, `est_high_usd`, `needs_web_verification` to `PASS_1_PROMPT` (Section 6.1).
   * Parse `estLowUsd` / `estHighUsd` via `PinSanitizer.sanitizePriceRange(...)` (default `10.0..16.0` if omitted) and `needsWebVerification = item.optBoolean("needs_web_verification", false)`.
6. Replace `executeWithRetry()` with strict retry rules:
   * **0 retries** on `SocketTimeoutException` / `InterruptedIOException` (rethrow immediately with `"Request timed out — using local/initial estimates"`).
   * **0 retries** on HTTP `400`/`401`/`403`/`404` (on `404`, message `"Gemini model '$model' not found — check Settings"`).
   * **At most 1 retry** on HTTP `429`/`503`: parse `resp.header("Retry-After")?.toLongOrNull()?.coerceIn(5L, 25L)?.times(1000L) ?: 10_000L`, delay once, and if it fails again throw `GeminiRateLimitException(resp.code, ...)`.

#### Step 6.3 — Smart 1–2 Call Orchestrator (`PinScanOrchestrator.kt`)
1. **Sequential Photo Processing:** Remove `Semaphore(2)` + `async/awaitAll` for Pass 1 in `emitAllStages()`. Process `photos` sequentially (`for ((photoIdx, pair) in photos.withIndex())`) emitting `"Scanning photo ${photoIdx + 1}/${photos.size}..."` so Pass 1 calls never fire concurrently.
2. **Immediate Pass 1 Valuation + Persistence for Standard Pins:**
   In `resolveLocally(...)`, when neither visual cache nor FTS metadata matches:
   * Compute `shouldVerify = candidate.needsWebVerification || candidate.estHighUsd >= 25.0 || candidate.rarityHint != "Common" || candidate.confidence < 0.70`.
   * Compute immediate mid & tier from `(candidate.estLowUsd, candidate.estHighUsd)`.
   * Persist the pin immediately to SQLite via `repository.upsertLearnedPin(...)` with `dataSource = "Gemini Estimate"`, `priceConfidence = "Peer Est."`, plus permanent crop & `hsv-dhash-v1` embedding — so even if Pass 2 is skipped or fails, the pin is already priced, clickable, and saved offline!
   * Return `ScannedPinResult` with:
     - `matchedPin = savedPin`, `marketLowUsd`, `marketHighUsd`, `estMarketValueUsd`, `priceTier` populated immediately!
     - `matchSource = if (shouldVerify && settings.autoVerifyOnline) MatchSource.PENDING_SEARCH else MatchSource.GEMINI_ESTIMATE`.
3. **Selective, Hard-Capped Pass 2 (Max 1 Call per Board, Top `<= 4` Pins):**
   * Collect all `PENDING_SEARCH` items across the batch.
   * Take **only the top `MAX_AUTO_VERIFY_PINS = 4`** items sorted by `compareByDescending<ScannedPinResult> { it.marketHighUsd }.thenBy { it.candidate.confidence }`.
   * Any remaining `PENDING_SEARCH` items beyond the top 4 are immediately downgraded to `MatchSource.GEMINI_ESTIMATE` (keeping their Pass 1 prices) — **no extra Pass 2 chunks!**
   * Decode Pass 2 crops at **`maxEdgePx = 192`, `quality = 75`**.
   * Issue **1 single call** to `geminiClient.researchPinValuations(...)`.
   * **Graceful Fallback / Circuit Breaker:** If that call fails (`GeminiRateLimitException`, timeout, or any error):
     - Set a concise non-blocking `pass2ErrorMessage` (e.g., `"Rate limit reached — showing Pass 1 estimates. Tap Refresh on any pin to verify online."`).
     - Downgrade all `PENDING_SEARCH` pins cleanly to `if (item.matchedPin != null && item.matchedPin.dataSource == "Seeded") MatchSource.LOCAL_DB else MatchSource.GEMINI_ESTIMATE` so **zero pins stay stuck on `"Searching..."`**.

#### Step 6.4 — UI Source Badge Update (`ScanResultScreen.kt` / `PinComponents.kt`)
1. Handle `MatchSource.GEMINI_ESTIMATE` cleanly in `ScanResultScreen.kt` (e.g., badge text `"Gemini Est."` with a subtle amber/blue tint, and price pill showing the Pass 1 `$low-$high` immediately rather than `"..."`).
2. Ensure the per-pin **Refresh** icon button works on `GEMINI_ESTIMATE` pins so the user can manually trigger a 1-pin `google_search` verification anytime.

#### Step 6.5 — Verification & Unit Tests
1. Update `GeminiParserTest.kt` to test:
   * Pass 1 parsing of `est_low_usd`, `est_high_usd`, `needs_web_verification`, and fallback defaults when omitted.
   * Pass 2 selection cap (`top <= 4` prioritized by highest `est_high_usd`, remaining downgraded to `GEMINI_ESTIMATE`).
2. Run:
   ```bash
   cd android
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. Manual validation on a 20-pin board photo (`PXL_20261123_024921794.MP.jpg`):
   * Confirm Google AI Studio dashboard shows **1–2 requests total** (never 10–27) and **0 retry storms**.
   * Confirm all ~20 pins display price pills within ~5 seconds after Pass 1.

---

## 8. Guardrails & Common Pitfalls for Implementation

1. **DO NOT** insert or update `est_market_value_usd` or `price_tier` in `pins` — SQLite computes them automatically.
2. **DO NOT** set `responseMimeType = "application/json"` on Pass 2 requests that include `"tools": [{"google_search": {}}]`. Parse JSON defensively from text instead.
3. **DO NOT** loop through all 20 pins in Pass 2 chunks — automatic Pass 2 MUST be capped at **1 single call for the top `<= 4` high-value/uncertain pins**.
4. **DO NOT** retry `SocketTimeoutException` or HTTP `400`/`401`/`403`/`404`, and **DO NOT** retry `429`/`503` more than once or faster than `Retry-After` / `10s`.
5. **DO NOT** overwrite `context.getDatabasePath("disney_pin_database.sqlite")` if it already exists — doing so destroys all Gemini-learned pins and user portfolio data.
6. **DO NOT** decode full 12MP camera photos directly into Compose `ImageBitmap` without downsampling (`inSampleSize` / max 1600px) — causes `OutOfMemoryError` on multi-photo uploads.
7. **ALWAYS** apply EXIF rotation (`ExifInterface`) BEFORE sending to Gemini and BEFORE cropping bounding boxes, otherwise bounding boxes will be rotated 90° relative to displayed photos.
8. **ALWAYS** run database and network work on `Dispatchers.IO` inside `viewModelScope` and update Compose UI state via immutable `StateFlow` snapshots.
9. **NEVER** commit a real Gemini API key to `build.gradle.kts`, `strings.xml`, or any tracked git file.
