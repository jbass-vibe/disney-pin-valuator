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

## 2. Target Architecture (Local-First + Gemini Hybrid)

```
 [ User: Take Photo / Upload 1..10 Photos ]
                     |
                     v
        [ ImageUtils (EXIF + <=1600px) ]
                     |
        +------------+------------+
        | (Offline?)              | (Online + API Key)
        v                         v
 [ Queue to pending_scans ]  [ GeminiApiClient: Pass 1 (Vision) ]
 [ + Local FTS5 / Cache   ]  (gemini-2.5-flash, JSON, thinkingBudget=0,
                              NO web search -> box_2d[0..1000] + attrs)
                                  |
                                  v
                     [ Crop each pin box -> Bitmap ]
                                  |
                                  v
            [ LocalPinMatcher (Pass 0 Visual + FTS5 Metadata) ]
                 |                                      |
      (Local Hit & Fresh <=60d)              (Miss OR Stale >60d)
                 |                                      |
                 v                                      v
      [ Emit Result Immediately ]       [ GeminiApiClient: Pass 2 (Search) ]
      [ source = LOCAL_DB       ]       (batched <=5 crops + google_search tool)
                                                        |
                                                        v
                                        [ Knowledge Extension Write-Back ]
                                        - INSERT new pin (GEM-XXXX) OR UPDATE price
                                        - SQLite triggers update pins_fts & price_history
                                        - Save crop JPEG to filesDir/pin_crops/
                                        - Store hsv-dhash-v1 BLOB in pin_embeddings
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
* OkHttp timeouts: `connectTimeout = 15s`, `readTimeout = 45s`, `writeTimeout = 30s`.
* Retry HTTP `429` and `503` up to 2 times with backoff (`1500ms`, `3500ms`).

### 6.1 Pass 1 — Fast Multi-Pin Detection & Attribute Extraction
* **Tools:** NONE (no web search — maximizes speed and bounding-box accuracy).
* **Generation Config:**
  ```json
  {
    "temperature": 0.1,
    "responseMimeType": "application/json",
    "thinkingConfig": { "thinkingBudget": 0 }
  }
  ```
* **Request `contents`:** `[ { "parts": [ { "inline_data": { "mime_type": "image/jpeg", "data": "<BASE64_1600PX_JPEG>" } }, { "text": "<PASS_1_PROMPT>" } ] } ]`
* **Prompt Instructions (`PASS_1_PROMPT`):**
  > You are a Disneyland / Walt Disney World collectible pin expert. Detect EVERY Disney trading pin visible in this image (up to 25 pins). Return ONLY valid JSON matching:
  > `{ "pins": [ { "box_2d": [ymin, xmin, ymax, xmax], "pin_name": "short character or subject name", "character": "main Disney character if any", "collection_guess": "e.g. Hidden Disney, Cast Hidden Disney, Disney Ice Cream, Attractions Alphabet, Open Edition", "sub_set_guess": "e.g. Ear Headbands, Ear Hats, Disneyland D, Film Anniversaries, Cupcakes, Sundaes", "resort_guess": "DLR or WDW or Unknown", "rarity_hint": "Common | Chaser / Completer | Super Chaser", "visual_tags": "comma-separated visual traits e.g. red Disneyland D frame, silver Hidden Mickey, purple hexagon", "has_hidden_mickey": true, "confidence": 0.85, "scrapper_risk_hint": "Low | Medium | High", "scrapper_notes": "brief visual note if dipped enamel/rough edges visible, else empty" } ] }`
  > Rules: `box_2d` coordinates MUST be integers normalized to `0..1000` (`[ymin, xmin, ymax, xmax]`). Do not merge adjacent pins into one box.

### 6.2 Pass 2 — Search-Grounded Identification & Valuation (Fallback Only)
* **Triggered ONLY for:** `MatchOutcome.Unmatched`, `MatchOutcome.LocalStale`, or manual **"Refresh Price"**.
* **Batching:** Up to **5 cropped pin images** per API call (each crop labeled `Pin #0`, `Pin #1`, ... with its Pass 1 guess and, if stale, its existing catalog name).
* **Tools:** `"tools": [{ "google_search": {} }]`
* **Generation Config:** `{ "temperature": 0.2 }` — **DO NOT set `responseMimeType: "application/json"` when `google_search` tool is enabled** (can trigger HTTP 400 on Gemini 2.5 models).
* **Prompt Instructions (`PASS_2_PROMPT`):**
  > Use Google Search to identify each numbered Disney pin crop and find its current secondary market value in USD from recent eBay sold listings, Mercari, and Disney pin collector guides. Return ONLY a single JSON object (no prose before or after):
  > `{ "valuations": [ { "temp_id": 0, "pin_name": "Official or commonly traded pin name", "collection": "Series or collection name", "sub_set": "Sub-set name or empty string", "resort": "DLR | WDW | DLR/WDW | Other", "year_wave": "Year or wave e.g. 2025 Wave A or 2023", "rarity": "Common | Chaser / Completer | Super Chaser", "market_low_usd": 14.0, "market_high_usd": 22.0, "price_confidence": "Verified | Peer Est. | Low Conf", "scrapper_risk": "Low | Medium | High", "visual_tags": "concise comma-separated visual descriptors", "notes": "1-sentence valuation & edition summary (e.g. LE 500, Hidden Mickey series, recent eBay comps)" } ] }`
* **Defensive Response Parsing:**
  1. Concatenate all `candidates[0].content.parts[].text`.
  2. Strip markdown fences (` ```json ... ``` `) and extract the substring from the first `{` to the matching last `}`.
  3. Extract citation sources from `candidates[0].groundingMetadata.groundingChunks[]` (`chunk.web.uri` + `chunk.web.title`), deduplicate, and store up to 5 `{title, url}` objects as JSON in `pins.valuation_sources`.
  4. Sanitize every numeric & enum field (`marketLowUsd >= 0`, `marketHighUsd = max(low, high)`, map invalid `rarity`/`priceConfidence`/`scrapperRisk` to safe defaults).

---

## 7. Phased Implementation Roadmap (5 Ordered Phases)

Execute phases **strictly in order**. Do not proceed to the next phase until the current phase's verification checks pass.

### Phase 1: Configuration, Database Migration & Repository Write Layer
**Files to create/edit:**
* `android/app/build.gradle.kts` — add OkHttp, ExifInterface, `buildConfig`, `testImplementation("org.json:json:20240303")`.
* `android/app/src/main/AndroidManifest.xml` — add `INTERNET` and `ACCESS_NETWORK_STATE`.
* `data/settings/SettingsRepository.kt` *(NEW)* — `SharedPreferences` wrapper for `geminiApiKey` (falling back to `BuildConfig.DEFAULT_GEMINI_API_KEY`), `geminiModel` (default `"gemini-2.5-flash"`), `stalePriceDays` (default `60`).
* `data/model/PinModels.kt` — extend `Pin` with `dataSource`, `referenceImagePath`, `valuationSources`; add `BoundingBoxNorm`, `DetectedPinCandidate`, `PinValuationSource`, `ScannedPinResult`, `MatchSource` (`LOCAL_VISUAL`, `LOCAL_DB`, `GEMINI_SEARCH`, `PENDING_SEARCH`), `BoardScanSummary`, `PendingScanItem`, `PinEmbeddingRecord`.
* `data/db/PinDatabaseHelper.kt` — enable `PRAGMA foreign_keys = ON`; add idempotent `user_version = 2` migration.
* `data/db/PinRepository.kt` — fix `getPriceHistory()` column bug; implement `upsertLearnedPin()`, `updatePinValuation()`, `upsertPinEmbedding()`, `getAllPinEmbeddings()`, `saveBoardScan()`, `getRecentBoardScans()`, `getScanDetections()`, `queuePendingScan()`, `getPendingScans()`, `deletePendingScan()`, and source filter in `searchPins()`.

**Phase 1 Verification:**
* Unit tests in `PinRepositorySanitizationTest.kt` verifying enum normalization, `GEM-XXXX` ID formatting, and `price_history` column mapping.
* `./gradlew testDebugUnitTest` passes.

---

### Phase 2: Image Preprocessing, Visual Fingerprinting & Local Matcher
**Files to create:**
* `domain/ImageUtils.kt` *(NEW)* — EXIF-aware bitmap decode & downscale to `<= 1600px` JPEG; base64 encoding; `descaleBoundingBox(box2d, width, height)` clamping `[0..1000]` safely to image bounds with 6% padding; `cropAndSavePinThumbnail(bitmap, box2d, outFile, maxDim = 320)`.
* `domain/VisualFingerprint.kt` *(NEW)* — Pure-Kotlin `compute(bitmap): ByteArray` (200-byte `hsv-dhash-v1`) and `similarity(a: ByteArray, b: ByteArray): Double`.
* `domain/LocalPinMatcher.kt` *(NEW)* — Pure-Kotlin scoring engine (`matchByVisual()`, `matchByMetadata()`, `isPriceFresh(priceUpdated, today, staleDays)`).

**Phase 2 Verification:**
* Unit tests in `LocalPinMatcherTest.kt` and `VisualFingerprintTest.kt`:
  - Bounding box `[100, 200, 300, 400]` on `1000x2000` image maps cleanly and clamps out-of-range inputs (`-50`, `1200`).
  - `VisualFingerprint.similarity(a, a) == 1.0`; distinct bitmaps score `< 0.6`.
  - `LocalPinMatcher` matches `"Hippo"` + `"Disneyland D"` to `PIN-0518` (`>= 0.72`) and rejects unrelated pins (`< 0.50`).
  - Stale-date calculation correctly classifies `30 days` as fresh and `90 days` as stale when threshold is `60`.

---

### Phase 3: Gemini REST Client & Hybrid Scan Orchestrator
**Files to create:**
* `data/remote/GeminiApiClient.kt` *(NEW)* — Implements `detectPinsInPhoto(jpegBase64, apiKey, model)` (Pass 1) and `researchPinValuations(cropsWithHints, apiKey, model)` (Pass 2 with `google_search`), defensive JSON parser (`extractJsonObject()`), `groundingChunks` citation extraction, and HTTP 429/503 exponential backoff.
* `domain/PinScanOrchestrator.kt` *(NEW)* — Coordinates single or multi-photo scan batches:
  1. Checks connectivity & API key; if unavailable, saves photos to `pending_scans` and emits offline state.
  2. Runs Pass 1 per photo (bounded concurrency `Semaphore(2)`).
  3. Crops each detected pin and runs `LocalPinMatcher`.
  4. Immediately emits progressive `ScanBatchState` with all `LOCAL_DB` / `LOCAL_VISUAL` hits populated and unresolved pins marked `PENDING_SEARCH`.
  5. Chunks unresolved/stale pins into batches of `<= 5` and calls Pass 2 (`researchPinValuations`).
  6. Writes every Pass 2 pin back into SQLite (`upsertLearnedPin` + saves crop JPEG + `upsertPinEmbedding`), logs `board_scans` & `scan_detections`, and emits final `ScanBatchState` sorted descending by `estMarketValueUsd`.
  7. Exposes `refreshSinglePin(pin, cropFile?)` for one-tap online price refreshes from results or Pin Detail.

**Phase 3 Verification:**
* Unit tests in `GeminiParserTest.kt`:
  - Parses valid Pass 1 multi-pin JSON response (15 pins).
  - Parses Pass 2 response wrapped in ```` ```json ... ``` ```` with leading/trailing prose and extracts `groundingMetadata.groundingChunks`.
  - Sanitizes invalid rarity (`"LE 300"` → `"Super Chaser"`), swapped prices (`low=40, high=20` → `low=20, high=40`), and missing optional fields without crashing.

---

### Phase 4: Trade-First UI Refactor & ViewModel Integration
**Files to create/edit:**
* `ui/viewmodel/PinValuatorViewModel.kt` — Inject/instantiate `SettingsRepository`, `GeminiApiClient`, `PinScanOrchestrator`; expose `scanBatchState`, `recentScans`, `pendingScans`, `settingsState`, `selectedSourceFilter`; add actions `scanPhotos(uris)`, `processPendingQueue()`, `openRecentScan(scanId)`, `refreshPinOnline(pin)`, `updatePinManually(...)`, `saveSettings(...)`.
* `ui/screens/ScanHomeScreen.kt` *(NEW — default home tab)* — Clean hero card with **Take Photo** (`TakePicture`) and **Upload Photos** (`PickMultipleVisualMedia(maxItems = 10)`), inline API-key setup prompt if key is blank, Offline Queue banner with **"Process Queued Scans"** button if `pendingScans.isNotEmpty()`, and **Recent Scans** list.
* `ui/screens/ScanResultScreen.kt` *(NEW)* —
  - Top bar showing batch summary (`"18 pins found • 2 Grails • 3 High"` + progress bar while Pass 2 runs).
  - `HorizontalPager` for multi-photo batches displaying `AnnotatedPinPhoto`: renders the photo with `ContentScale.Fit`, computes exact displayed image rect, and draws high-contrast clickable price pills (`★ $50+`, `★ $30-$49`, `$20-$29`, `<$20`, `...` while searching) at each pin's normalized `[ymin, xmin, ymax, xmax]` center-bottom.
  - Below photo: **Trade Priority List** sorted highest `estMarketValueUsd` first across all photos in the batch, highlighting the tapped pill's card, displaying thumbnail crop, price range, tier badge, `Local DB` / `Gemini Web` pill, scrapper warning, and quick buttons (**Details**, **Refresh**, **Edit**, **Save**).
* `ui/screens/SettingsDialog.kt` *(NEW)* — Modal dialog from TopAppBar gear icon: Gemini API key field (masked), model name dropdown/input (`gemini-2.5-flash` default), stale-price threshold slider (14–180 days), DB growth stats (`Seeded: 595 • Learned: N • Visual Index: M`).
* `ui/screens/EditPinDialog.kt` *(NEW)* — Lightweight dialog to correct a pin's name, collection, low/high USD, rarity, or scrapper risk directly in SQLite.
* `ui/screens/MyPinsScreen.kt` *(NEW)* — Clean segmented control (`Portfolio` | `Trades & ISO`) hosting existing `PortfolioScreen` and `TradesScreen`.
* `ui/screens/ManualAppraisalScreen.kt` *(RENAMED from `ScannerScreen.kt`)* — Preserves existing scrapper/grading checklist as an optional route navigated to from `PinDetailScreen`.
* `ui/screens/CatalogScreen.kt` — Add source filter chips (`All`, `Seeded`, `Gemini Learned`) and display local crop thumbnail on `PinCard` when `pin.referenceImagePath` exists.
* `ui/screens/PinDetailScreen.kt` — Display cropped reference image, clickable web source citations (`Intent.ACTION_VIEW`), fixed Price History section, **"Refresh Price with Gemini"** button, and **"Edit Pin"** button.
* `MainActivity.kt` — Update `BottomTab` to 3 tabs (`SCAN`, `CATALOG`, `MY_PINS`), set `startDestination = BottomTab.SCAN.route`, add routes `"scan_result"`, `"detail"`, `"manual_appraisal"`, and TopAppBar Settings icon.

---

### Phase 5: Build Verification & Real-Photo Validation
1. **Automated Checks:**
   ```bash
   cd android
   ./gradlew testDebugUnitTest assembleDebug
   ```
   All unit tests (existing `EvaluationEngineTest` + new `LocalPinMatcherTest`, `VisualFingerprintTest`, `GeminiParserTest`, `PinRepositorySanitizationTest`) and debug APK build MUST pass with zero errors.
2. **Real-Photo Functional Verification Checklist:**
   * **Test A (4-Pin Palm Photo — `PXL_20261123_030126127.MACRO_FOCUS.jpg`):** Upload photo → confirms `Hippo` (`PIN-0518`, `$57.50 Grail`) and `Mater` (`PIN-0484`, `$15.00`) resolve via `Local DB`; missing pins (e.g., Donald Cupcake, Moana/Maui Headband) resolve via `Gemini Web`, display source citations, and are inserted into `pins` (`GEM-XXXX`) + `pin_embeddings`.
   * **Test B (Knowledge Extension Re-Scan):** Re-upload the exact same 4-pin photo → ALL 4 pins resolve via `Local DB` / `Local Visual` with **zero Pass 2 web calls**.
   * **Test C (20-Pin Cast Member Board — `PXL_20261123_024921794.MP.jpg`):** Upload full board photo → renders readable, non-crashing color-coded price pills on the Mickey board and ranks top trade candidates at the top of the list.
   * **Test D (Multi-Photo Batch):** Upload both photos simultaneously → horizontal pager switches between annotated photos; priority list merges & ranks pins from both photos.
   * **Test E (Offline Mode):** Enable Airplane Mode → uploading a photo queues it cleanly to `pending_scans`; Catalog search and Recent Scans remain 100% functional; disabling Airplane Mode lets user tap **"Process Queued Scans"** to resolve them.

---

## 8. Guardrails & Common Pitfalls for Implementation

1. **DO NOT** insert or update `est_market_value_usd` or `price_tier` in `pins` — SQLite computes them automatically.
2. **DO NOT** set `responseMimeType = "application/json"` on Pass 2 requests that include `"tools": [{"google_search": {}}]`. Parse JSON defensively from text instead.
3. **DO NOT** overwrite `context.getDatabasePath("disney_pin_database.sqlite")` if it already exists — doing so destroys all Gemini-learned pins and user portfolio data.
4. **DO NOT** decode full 12MP camera photos directly into Compose `ImageBitmap` without downsampling (`inSampleSize` / max 1600px) — causes `OutOfMemoryError` on multi-photo uploads.
5. **ALWAYS** apply EXIF rotation (`ExifInterface`) BEFORE sending to Gemini and BEFORE cropping bounding boxes, otherwise bounding boxes will be rotated 90° relative to displayed photos.
6. **ALWAYS** run database and network work on `Dispatchers.IO` inside `viewModelScope` and update Compose UI state via immutable `StateFlow` snapshots.
7. **NEVER** commit a real Gemini API key to `build.gradle.kts`, `strings.xml`, or any tracked git file.
