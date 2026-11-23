# Software Requirements Specification (SRS)
## Disney Pin Quick-Valuator & Trade Assistant

**Version:** 2.0  
**Status:** Approved for Implementation  
**Target Platform:** Native Android (Kotlin + Jetpack Compose, `minSdk 30`, `targetSdk 35`)  
**Target Domain:** Real-Time Disneyland / Disney Parks Pin Trading & Valuation  

---

## Revision History

| Version | Date | Summary of Changes |
| :--- | :--- | :--- |
| **1.0** | 2026-11-23 | Initial draft describing a 20-week multi-service cloud platform (Web/iOS/Android, PostgreSQL, MongoDB, Qdrant, PyTorch/CLIP, marketplace web crawlers, manual scrapper/grading workflows). |
| **2.0** | 2026-11-23 | **Major scope and architecture realignment** based on primary user objective and uploaded repository sources (`android/` Kotlin/Compose app + `disney_pin_database.sqlite` + real-world park photos):<br>• Pivoted to a **fast, simple, photo-first mobile trading tool** (single-pin, handful, and full 15–25 pin board photos; single or multi-photo batch upload).<br>• Replaced unimplemented cloud microservices/crawlers with a **Local-First SQLite → Gemini Vision + Google Search Grounding hybrid pipeline**.<br>• Added **automatic offline knowledge extension**: every Gemini identification/valuation is written back into the local SQLite catalog (metadata, pricing, sources, cropped thumbnail, and visual fingerprint) so future lookups work locally.<br>• De-emphasized heavy manual scrapper/condition checklists into an optional Pin Detail action while making the **Scan** screen the default home screen.<br>• Documented fixes for bugs found in the v1.0 Android codebase (dead photo pickers, broken `price_history` column mapping, missing `INTERNET` permission, read-only `pins` table). |

---

## 1. Introduction & Product Objective

### 1.1 Primary Objective
Enable a Disney pin trader standing at a park pin board, lanyard, or trading table to **take or upload one or more photos of pins and immediately see market valuations**—highlighting which pins are worth trading for—through a **fast, efficient, and simple interface**.

### 1.2 Core Operating Principle: Local-First + Gemini Knowledge Growth
1. **Local First:** Match detected pins against the bundled offline SQLite catalog (`disney_pin_database.sqlite`, initially 595 pins) and any locally stored visual fingerprints (`pin_embeddings`) first for instant, zero-cost results.
2. **Gemini Fallback:** Whenever a pin is **not in the local database**, has a **low-confidence match**, or has a **stale price** (older than a configurable window, default 60 days), use **Gemini multimodal vision + Google Search grounding** to visually identify the pin and estimate current secondary-market value (eBay sold listings, Mercari, PinPics/collector benchmarks).
3. **Extend Offline Knowledge:** Automatically persist every newly identified pin, updated price range, source citation, cropped reference image, and visual fingerprint back into the local SQLite database so the offline catalog continuously grows smarter with every scan.

### 1.3 Real-World Photo Scenarios Supported
Based on actual park trading photos, the app MUST handle all three layouts seamlessly:
* **Full Cast Member Pin Board:** 10–25 pins arranged on a Mickey-shaped foam board or corkboard.
* **Small Cluster / Handful:** 2–6 pins held in a palm or laid on a table, potentially rotated or adjacent.
* **Single Pin (Front or Front + Back):** Close-up of an individual pin for rapid lookup or detailed check.
* **Multi-Photo Batch:** 2–10 photos uploaded at once (e.g., multiple boards or front/back shots) evaluated in one unified trade priority list.

### 1.4 Explicit Out-of-Scope (v2.0)
To keep the application fast, simple, and maintainable, the following v1.0 items are explicitly **excluded** from v2.0:
* Custom backend servers, cloud databases (PostgreSQL, MongoDB, Qdrant, Elasticsearch), or OAuth2 user accounts.
* Custom-trained PyTorch/OpenCLIP/OpenCV native models bundled in the APK.
* Scheduled marketplace web crawlers (Scrapy, Playwright, Celery/Redis).
* Formal PDF appraisal report generation and social/multi-user trading networks.

---

## 2. Domain Data Rules & Valuation Tiers

All valuation logic MUST align with the existing SQLite schema in `android/app/src/main/assets/disney_pin_database.sqlite`:

### 2.1 Price Tiers (Auto-Computed in SQLite)
`est_market_value_usd` is a SQLite `STORED` generated column: `ROUND((market_low_usd + market_high_usd) / 2.0, 2)`.  
`price_tier` is a SQLite `STORED` generated column with four tiers and corresponding trade-priority visual cues:

| Price Tier (`price_tier`) | Midpoint Range | Trade Priority Cue | Overlay Badge Color |
| :--- | :--- | :--- | :--- |
| `Grail ($50+)` | `>= $50.00` | **Must Trade** (`★ $50+ ★`) | Magenta/Red `#FF0F4B` with Gold border |
| `High ($30-$49)` | `$30.00 – $49.99` | **Strong Trade** (`★ $30-$49`) | Bright Gold `#FFDE00` with Dark text |
| `Good ($20-$29)` | `$20.00 – $29.99` | **Solid Trade** (`$20-$29`) | Emerald Green `#00B955` with White text |
| `Standard (<$20)` | `< $20.00` | **Common / Low Priority** (`<$20`) | Muted Slate `#262A32` with Light text |
| *(Low Confidence)* | Any | **Verify Before Trading** (`? $X-$Y`) | Amber/Orange `#EA580C` with White text |

### 2.2 Allowed Enum Values (SQLite `CHECK` Constraints)
To avoid SQLite constraint violations when writing Gemini results back to `pins`, all inserted/updated rows MUST normalize to these exact values:
* `rarity`: `'Common'`, `'Chaser / Completer'`, `'Super Chaser'` *(Limited Edition / Artist Proof / Cast Exclusive details from Gemini are preserved in `notes` and `visual_tags`, while mapping LE ≤ 500 or AP/PP to `'Super Chaser'` and LE > 500 or Chaser/Completer to `'Chaser / Completer'`).*
* `price_confidence`: `'Verified'`, `'Peer Est.'`, `'Low Conf'`
* `scrapper_risk`: `'Low'`, `'Medium'`, `'High'`

---

## 3. Functional Requirements

Requirements are prioritized as **P0 (Must-Have Core)** and **P1 (Secondary / Preserved)**.

### 3.1 Fast Single & Multi-Photo Input (P0)
* **FR-1.1 Multi-Photo Upload:** Users can select **1 to 10 photos** at once from the device gallery using Android Photo Picker (`PickMultipleVisualMedia`).
* **FR-1.2 One-Tap Camera Capture:** Users can capture a pin board or pin photo directly with the camera (`TakePicture` via `FileProvider`) and immediately begin valuation without filling out any form.
* **FR-1.3 Automatic Image Preprocessing:** Before analysis or cropping, each image MUST be EXIF-orientation normalized and downscaled to a maximum of **1600 px** on the longest edge (JPEG quality 85%) to keep latency and payload size low.
* **FR-1.4 Multi-Pin Detection:** A single photo MUST support detecting and valuing anywhere from **1 to 25 pins** simultaneously, returning a bounding box for each pin.

### 3.2 Hybrid Local-First → Gemini Visual Match & Valuation Engine (P0)
* **FR-2.1 Local Visual Fingerprint Cache (Pass 0):** When local cropped reference fingerprints exist in `pin_embeddings` (pure-Kotlin perceptual hash + HSV color descriptor), compare detected pin crops against local embeddings first for instant offline recognition.
* **FR-2.2 Fast Gemini Vision Detection (Pass 1):** Send the preprocessed photo to the Gemini REST API (`gemini-2.5-flash` default, user-configurable) in structured JSON mode with minimal thinking latency (`thinkingBudget = 0`) and **no web search** to rapidly detect all visible pins:
  * Normalized bounding box `[ymin, xmin, ymax, xmax]` in `0..1000` coordinate space.
  * Pin attributes: `pin_name`, `character`, `collection_guess`, `sub_set_guess`, `resort_guess`, `visual_tags`, `rarity_hint`, `has_hidden_mickey`, `visual_confidence`, and visible `scrapper_risk_hint`.
* **FR-2.3 Local SQLite Catalog Resolution:** For each pin detected in Pass 1:
  1. Check local visual fingerprint similarity against `pin_embeddings` (if crop matches with high similarity `>= 0.84`).
  2. Query local `pins_fts` (FTS5) and score candidate rows in `pins` by weighted overlap across `pin_name`, `collection`, `sub_set`, `resort`, and `visual_tags` (match threshold `>= 0.72`).
  3. If a confident local match is found **and** `price_updated` is within the freshness window (default `<= 60` days), immediately resolve using local SQLite data (`match_source = LOCAL_DB`) with **zero online search calls**.
* **FR-2.4 Gemini + Google Search Grounding Fallback (Pass 2):** Triggered **only** for pins that have (a) no confident local DB match, (b) a stale local price (`> 60` days old), or (c) an explicit user tap on **"Refresh Online"**:
  * Crop each unresolved pin from the photo using its Pass 1 bounding box and send up to 5 crops per batch request to Gemini with the **Google Search grounding tool** (`tools: [{"google_search": {}}]`).
  * Return verified pin name, collection/series, resort, year/wave, rarity, current market low/high USD (based on recent sold/market listings), confidence level, scrapper risk assessment, visual tags, and grounding citation URLs.
* **FR-2.5 Progressive Streaming Results:** Local DB matches MUST render on the annotated photo and priority list immediately after Pass 1, while unresolved pins display a lightweight *"Searching market..."* badge and update in place as Pass 2 completes.
* **FR-2.6 Graceful Offline Mode:** When no internet connection is available or no Gemini API key is configured:
  * Clearly display an **Offline Mode** status indicator.
  * Allow instant offline FTS5 text lookup and local visual matching against previously indexed pins.
  * Allow users to save/queue uploaded photos to a **Pending Online Lookup** queue (`pending_scans`) and resolve them with one tap once connectivity returns.

### 3.3 Offline Database & Knowledge Extension (P0)
* **FR-3.1 Persist Newly Discovered Pins:** Every pin newly identified by Gemini (Pass 2) MUST be inserted into the local `pins` table with a generated `pin_id` (`GEM-0001`, `GEM-0002`, ...), `data_source = 'Gemini Search'`, and JSON-encoded `valuation_sources` URLs. Inserting into `pins` MUST automatically fire the existing `pins_ai` trigger so the pin is immediately searchable via `pins_fts`.
* **FR-3.2 Update Existing Pin Prices:** When Gemini refreshes an existing pin's price, `UPDATE pins` MUST update `market_low_usd`, `market_high_usd`, `price_updated`, `price_confidence`, and `valuation_sources`. The existing `pins_price_audit` trigger MUST record the change into `price_history`, and SQLite generated columns MUST automatically recompute `est_market_value_usd` and `price_tier`.
* **FR-3.3 Persist Visual Crops & Fingerprints:** For every confirmed/identified pin with a valid bounding box, save a cropped JPEG thumbnail (`filesDir/pin_crops/<pin_id>.jpg`, max 320 px), compute its pure-Kotlin visual fingerprint (`hsv-dhash-v1`), and store it in `pin_embeddings` plus `pins.reference_image_path`.
* **FR-3.4 Deduplication & Manual Correction:** Before inserting a new pin, check for an existing match by normalized `(pin_name, collection, sub_set)` to update rather than duplicate. Users MUST be able to edit a pin's name, set, or market low/high USD directly from scan results or Pin Detail, immediately updating SQLite.
* **FR-3.5 Scan History Persistence:** Log every completed photo scan into `board_scans` (image path, pin count, total low/mid/high USD) and each detected pin into `scan_detections` (bounding box JSON, matched `pin_id`, confidence, match source, price snapshot) so users can revisit recent board scans instantly offline.

### 3.4 Fast, Simple, Trade-Decision-First UI (P0)
* **FR-4.1 Scan-First Home Screen:** Launch directly into the **Scan** tab (not text catalog). The home screen presents only:
  * Primary CTAs: **Take Photo** and **Upload Photos (1+)**
  * Offline queue banner (only shown when queued photos exist)
  * Compact **Recent Scans** list (tap to reopen any annotated board scan)
* **FR-4.2 Interactive Annotated Photo Overlay:** Display each uploaded photo with high-contrast, color-coded price pills positioned at each pin's bounding box. For multi-photo batches, provide a clean horizontal pager/thumbnail strip to swipe between photos. Tapping any pill selects and highlights that pin in the list below.
* **FR-4.3 Value-Ranked Trade Priority List:** Display all detected pins across the current scan batch sorted **highest `est_market_value_usd` first**. Each row shows:
  * Cropped pin thumbnail
  * Pin name & collection/set
  * Prominent price range (`$45–$70`) + tier badge (`Grail`, `High`, `Good`, `Standard`)
  * Source pill (`Local DB` vs `Gemini Web`) and scrapper warning badge if `Medium`/`High`
  * One-tap actions: **Pin Details**, **Refresh Online**, **Edit**, **Save to My Pins**
* **FR-4.4 Simplified 3-Tab Navigation + Settings:**
  * **Scan** (default): Camera/Upload, active scan results, recent scans.
  * **Catalog**: Browse & FTS5 search all offline pins (both seeded 595 pins and Gemini-learned pins) with thumbnails, price-tier chips, and source filter (`All` | `Seeded` | `Gemini Learned`).
  * **My Pins**: Combines Portfolio and Traders/ISO Matcher via a clean top segmented control.
  * **Settings (TopAppBar icon):** Configure Gemini API key, Gemini model ID (default `gemini-2.5-flash`), price freshness window in days (default `60`), view DB stats (Seeded vs. Gemini-Learned pin count, cached visual embeddings), and test API connection.

### 3.5 Preserved Secondary Features & Bug Fixes (P1)
* **FR-5.1 Fix `price_history` Schema Bug:** Update `PinRepository.getPriceHistory()` to query the actual SQLite columns (`history_id`, `pin_id`, `market_low_usd`, `market_high_usd`, `est_market_value_usd`, `observed_date`, `source`) instead of the non-existent `id/old_low_usd/old_high_usd/changed_at` columns.
* **FR-5.2 Pin Detail Enhancements:** Show cropped reference thumbnail (when available), clickable web source citations (from Gemini grounding), accurate price history, and a one-tap **"Refresh Price with Gemini"** button.
* **FR-5.3 Optional Scrapper & Condition Check:** Retain the existing `EvaluationEngine` manual scrapper/condition grading workflow as an optional secondary screen launched from Pin Detail (`"Run Scrapper & Grade Check"`), removing it from the main bottom navigation bar.
* **FR-5.4 Portfolio & Trade Parity Matching:** Preserve existing `PortfolioScreen` and `TradesScreen` functionality inside the **My Pins** tab without regression.

---

## 4. Non-Functional Requirements

### 4.1 Performance & Latency
* Local SQLite FTS5 search and visual fingerprint lookup: `< 100 ms`.
* Pass 1 (Gemini Vision multi-pin detection on 1600 px JPEG): target `< 4 seconds` on LTE/Wi-Fi.
* Pass 2 (Gemini Search Grounding for unknown pins, up to 5 per batch): target `< 8 seconds` per batch, without blocking display of local hits.
* Multi-photo batch processing: process photos with bounded concurrency (max 2 concurrent network requests) and show per-photo progress (`"Analyzing photo 2 of 3..."`).

### 4.2 Data Integrity & Persistence
* **Never wipe user-learned data:** Copy `disney_pin_database.sqlite` from assets only on first install; apply all schema additions via idempotent `PRAGMA user_version` migrations.
* Enable `PRAGMA foreign_keys = ON` on every database open.
* Never attempt to `INSERT` or `UPDATE` SQLite `GENERATED ALWAYS AS` columns (`est_market_value_usd`, `price_tier`).

### 4.3 Security & Cost Control
* Never hardcode or commit a Gemini API key in source control. Support user entry in Settings (stored privately in app preferences) with optional build-time default via `local.properties` (`GEMINI_API_KEY`, gitignored).
* Minimize Gemini Search Grounding costs by strictly gating Pass 2 to local misses, stale prices, or explicit user refreshes.
* Handle HTTP `429` / `503` / network timeouts gracefully with exponential backoff (up to 2 retries) and clear user-facing error banners.

---

## 5. Acceptance Criteria

A build satisfies v2.0 when all of the following pass:
1. **Build & Unit Tests:** `./gradlew testDebugUnitTest assembleDebug` succeeds with zero errors.
2. **Multi-Pin Board Scan:** Uploading a 15–25 pin board photo (e.g., `PXL_20261123_024921794.MP.jpg`) renders clickable color-coded price pills over detected pins and a value-sorted list placing High/Grail pins at the top.
3. **Hybrid Local + Gemini Resolution:** Uploading the 4-pin palm photo (`PXL_20261123_030126127.MACRO_FOCUS.jpg`) matches `Hippo` (`PIN-0518`, `$57.50 Grail`) and `Mater` (`PIN-0484`, `$15.00`) from the local SQLite DB, uses Gemini Search Grounding for any pin absent from local DB, and displays source badges (`Local DB` vs `Gemini Web`).
4. **Offline Knowledge Extension Verified:** Immediately after Gemini identifies a previously unknown pin, that pin appears in the **Catalog** tab under `Gemini Learned` with its cropped thumbnail, is searchable via FTS5, and a subsequent scan of the same pin resolves via `Local DB` without invoking Pass 2 web search.
5. **Multi-Photo Batch:** Selecting 2+ photos at once processes all photos and presents a combined value-ranked trade list plus photo switcher.
6. **Offline Resilience:** With Airplane Mode enabled, the app remains usable for catalog lookup, local visual cache matches, viewing recent scans, and queueing new photos for later online resolution.
7. **Price History Fixed:** Opening any pin in `PinDetailScreen` displays its baseline and updated `price_history` rows accurately.
