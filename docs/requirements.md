# Software Requirements Specification (SRS)
## Disneyland Collector Pin Evaluation & Appraisal System

**Version:** 1.0  
**Status:** Draft  
**Target Domain:** Disney Pin Trading & Collecting Community  

---

## 1. Introduction & Executive Summary

### 1.1 Purpose
The **Disneyland Collector Pin Evaluation System** is a digital platform designed to automate and standardize the identification, condition grading, authenticity verification, and market valuation of Disneyland and Disney Parks collectible pins. 

### 1.2 System Scope
The system will provide collectors, appraisers, and traders with:
* Automated pin identification via computer vision and metadata matching.
* Counterfeit ("scrapper") risk assessment algorithms.
* Standardized condition grading metrics.
* Real-time and historical market valuation tracking.
* Portfolio management and trade matching tools.

---

## 2. Domain & Key Concepts

Disney Pin Trading features unique attributes that directly impact pin authenticity, rarity, and value:
* **Edition Type:** Open Edition (OE), Limited Edition (LE), Limited Release (LR), Artist Proof (AP), Pre-Production (PP), Cast Member Exclusive, Hidden Mickey / Lanyard Pin.
* **Physical Identifiers:** Waffle/sand swirl backing patterns, pin posts/nubs, enameling depth, stamped copyright/serial numbers, metal base color (gold, silver, black nickel, bronze).
* **Scrapper / Counterfeit Flags:** Rough edges, soft/dipped enamel, incorrect font stamps, missing or misaligned nubs, paint bleeding, off-color enamel, non-magnetic/weight discrepancies.

---

## 3. Functional Requirements

### 3.1 Pin Identification Engine
* **FR-1.1 Visual Search:** Users can upload front and back photos of a pin to search the master pin registry using feature matching and AI image recognition.
* **FR-1.2 Attribute Filtering:** Search filters supporting Pinpics ID, SKU, Release Year, Park/Resort (Disneyland Resort, Walt Disney World, Tokyo Disney Resort, Disneyland Paris, etc.), Event, Character, and Limited Edition size.
* **FR-1.3 OCR Tag Reading:** Extract text from backstamp images (e.g., "LIMITED EDITION OF 500", "FAC-XXXXXX-XXXXX", "© DISNEY").

### 3.2 Authenticity & Counterfeit ("Scrapper") Risk Assessment
* **FR-2.1 Backstamp Pattern Analysis:** Analyze photo of pin back against verified reference samples for waffle pattern clarity, border margin consistency, and logo alignment.
* **FR-2.2 Color & Fill Consistency Check:** Detect paint dips, pinholes, color tone variances, and enamel bleed compared to reference originals.
* **FR-2.3 Physical Metrics Input:** Allow manual or sensor input for weight (grams), thickness (mm), and magnetic properties to flag alloy anomalies.
* **FR-2.4 Scrapper Risk Score:** Output a normalized risk rating (`Low Risk`, `Moderate Risk`, `High Risk / Likely Scrapper`) with detailed reasoning.

### 3.3 Condition Grading Module
* **FR-3.1 Automated & Manual Grading:** Provide a standardized 10-point scale:
  * **Grade 10 (Gem Mint):** Flawless enamel, pristine card backing, original packaging.
  * **Grade 8-9 (Near Mint / Excellent):** Minor hairline factory scratches under magnification; unbent posts.
  * **Grade 5-7 (Good / Fine):** Moderate surface wear, faint enamel oxidation, minor post wobble.
  * **Grade 1-4 (Poor / Damaged):** Missing enamel, bent/broken posts, heavy oxidation, altered backing.
* **FR-3.2 Pin Card / Packaging Assessment:** Evaluate original card backer, OEM plastic wrap, or commemorative box condition.

### 3.4 Valuation & Pricing Engine
* **FR-4.1 Data Aggregation:** Scrape and index completed/sold listings from public secondary marketplaces (eBay, Mercari, Heritage Auctions, specialty forums).
* **FR-4.2 Valuation Algorithm:** Calculate Estimated Fair Market Value (FMV), historical price trend curves, low/high range, and liquidity score (how quickly the pin sells).
* **FR-4.3 Rarity & Demand Multipliers:** Adjust baseline value according to LE size, vintage status, character popularity (e.g., Haunted Mansion, Figment, Stitch), and current park anniversary trends.

### 3.5 Portfolio & Trade Management
* **FR-5.1 Personal Collection Tracking:** Users can catalog their pins, attach purchase prices, grade ratings, and track total portfolio net worth over time.
* **FR-5.2 Want & Trade List Matching:** Auto-match collectors based on complementary "Traders" and "ISO (In Search Of)" lists with fair-value parity indicators.

---

## 4. Non-Functional Requirements

### 4.1 Performance & Latency
* Image search recognition response time $< 2.5$ seconds.
* Valuation metric calculation updates daily for indexed pins.

### 4.2 Security & Data Integrity
* Authenticated access for portfolio management.
* Image provenance checks to prevent duplicate scam listings in trading modules.

### 4.3 Scalability & Reliability
* Support catalog storage of $150,000+$ pin references and millions of user portfolio items.
* High-availability storage for high-resolution macro imagery.

---

## 5. User Persona Matrix

| Persona | Primary Goal | Key Feature Used |
| :--- | :--- | :--- |
| **Casual Park Trader** | Quickly check if a park lanyard pin is a scrapper | Scrapper Risk Assessment & Visual Search |
| **Avid Collector** | Catalog collection and track net portfolio value | Portfolio Tracker & Historical Price Charts |
| **Reseller / Appraiser** | Grade pin condition and calculate fair market value | Valuation Engine & Grade Calculator |
| **Pin Trader Community** | Find balanced pin-for-pin trades | Trade Matcher & Want List |
