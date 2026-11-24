package com.disneypin.valuator.domain

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.disneypin.valuator.data.db.PinRepository
import com.disneypin.valuator.data.db.PinSanitizer
import com.disneypin.valuator.data.model.AppSettings
import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.LearnedPinInput
import com.disneypin.valuator.data.model.MatchSource
import com.disneypin.valuator.data.model.PhotoScanResult
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.ScanBatchState
import com.disneypin.valuator.data.model.ScannedPinResult
import com.disneypin.valuator.data.remote.GeminiApiClient
import com.disneypin.valuator.data.remote.GeminiRateLimitException
import com.disneypin.valuator.data.remote.Pass2CropRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class PinScanOrchestrator(
    private val context: Context,
    private val repository: PinRepository,
    private val geminiClient: GeminiApiClient
) {

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun scanPhotoUris(
        uris: List<Uri>,
        settings: AppSettings
    ): Flow<ScanBatchState> = flow {
        val savedPhotos = mutableListOf<Pair<File, Bitmap>>()
        uris.take(10).forEachIndexed { idx, uri ->
            val saved = ImageUtils.saveNormalizedScanImage(
                context = context,
                uri = uri,
                filename = "scan_${System.currentTimeMillis()}_$idx.jpg"
            )
            if (saved != null) savedPhotos.add(saved)
        }
        if (savedPhotos.isEmpty()) {
            emit(ScanBatchState(isScanning = false, errorMessage = "Could not decode selected image(s)."))
            return@flow
        }

        if (!isOnline() || settings.geminiApiKey.isBlank()) {
            savedPhotos.forEach { (file, _) ->
                repository.queuePendingScan(file.absolutePath)
            }
            val reason = if (settings.geminiApiKey.isBlank()) {
                "Gemini API key not configured. Saved ${savedPhotos.size} photo(s) to Offline Queue."
            } else {
                "Offline mode: Saved ${savedPhotos.size} photo(s) to Pending Online Lookup queue."
            }
            emit(ScanBatchState(isScanning = false, statusMessage = reason, errorMessage = reason))
            return@flow
        }

        emitAllStages(savedPhotos, settings).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun processPendingFiles(
        files: List<Pair<Long, String>>,
        settings: AppSettings
    ): Flow<ScanBatchState> = flow {
        if (!isOnline() || settings.geminiApiKey.isBlank()) {
            emit(ScanBatchState(isScanning = false, errorMessage = "Internet and Gemini API key required to process queue."))
            return@flow
        }
        val loaded = files.mapNotNull { (id, path) ->
            val bmp = ImageUtils.decodeNormalizedFile(path, maxEdgePx = 1600) ?: return@mapNotNull null
            id to (File(path) to bmp)
        }
        if (loaded.isEmpty()) {
            emit(ScanBatchState(isScanning = false, errorMessage = "No valid queued images found."))
            return@flow
        }
        emitAllStages(loaded.map { it.second }, settings).collect { state ->
            emit(state)
            if (!state.isScanning && state.errorMessage == null) {
                loaded.forEach { (id, _) -> repository.deletePendingScan(id) }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun retryScanBatch(
        previousState: ScanBatchState,
        settings: AppSettings
    ): Flow<ScanBatchState> = flow {
        if (!isOnline() || settings.geminiApiKey.isBlank()) {
            emit(
                previousState.copy(
                    isScanning = false,
                    errorMessage = "Internet connection and Gemini API key required to retry."
                )
            )
            return@flow
        }
        val failedPhotoFiles = previousState.photos
            .filter { it.pins.isEmpty() && it.errorMessage != null }
            .mapNotNull { photo ->
                val bmp = ImageUtils.decodeNormalizedFile(photo.imagePath, maxEdgePx = 1600) ?: return@mapNotNull null
                File(photo.imagePath) to bmp
            }

        if (failedPhotoFiles.isNotEmpty()) {
            emitAllStages(failedPhotoFiles, settings).collect { emit(it) }
            return@flow
        }

        // Otherwise Pass 1 succeeded and Pass 2 timed out / failed: re-run Pass 2 on top <= 4 pins
        val allPins = previousState.photos.flatMap { it.pins }
        val candidatesForVerify = selectTopForVerification(
            allPins.filter {
                it.matchSource == MatchSource.GEMINI_ESTIMATE || it.matchSource == MatchSource.PENDING_SEARCH
            },
            MAX_AUTO_VERIFY_PINS
        )
        if (candidatesForVerify.isEmpty()) {
            val allPhotos = previousState.photos.mapNotNull { photo ->
                val bmp = ImageUtils.decodeNormalizedFile(photo.imagePath, maxEdgePx = 1600) ?: return@mapNotNull null
                File(photo.imagePath) to bmp
            }
            if (allPhotos.isNotEmpty()) {
                emitAllStages(allPhotos, settings).collect { emit(it) }
            }
            return@flow
        }

        emit(
            previousState.copy(
                isScanning = true,
                statusMessage = "Retrying online verification for top ${candidatesForVerify.size} pin(s)...",
                errorMessage = null
            )
        )

        val workingPhotos = previousState.photos.toMutableList()
        val requests = candidatesForVerify.mapIndexedNotNull { idx, item ->
            val bmp = ImageUtils.decodeNormalizedFile(item.cropImagePath, maxEdgePx = 192) ?: return@mapIndexedNotNull null
            Pass2CropRequest(
                tempId = idx,
                jpegBase64 = ImageUtils.bitmapToJpegBase64(bmp, quality = 75),
                pass1Hint = "${item.candidate.pinName} ${item.candidate.collectionGuess} ${item.candidate.subSetGuess}".trim(),
                existingCatalogName = item.matchedPin?.pinName
            )
        }

        val valuationsResult = runCatching {
            geminiClient.researchPinValuations(requests, settings.geminiApiKey, settings.geminiModel)
        }

        if (valuationsResult.isFailure) {
            val err = valuationsResult.exceptionOrNull()
            emit(
                previousState.copy(
                    isScanning = false,
                    statusMessage = "Retry failed (Pass 1 estimates)",
                    errorMessage = "${err?.message ?: "Online verification unavailable"} — tap Retry to try again."
                )
            )
            return@flow
        }

        val valuations = valuationsResult.getOrDefault(emptyList())
        val byTempId = valuations.associateBy { it.tempId }
        candidatesForVerify.forEachIndexed { idx, item ->
            val valResult = byTempId[idx] ?: valuations.getOrNull(idx) ?: return@forEachIndexed
            val citationsJson = PinSanitizer.encodeCitationsJson(valResult.citations)
            val savedPin = if (item.matchedPin != null) {
                repository.updatePinValuation(
                    pinId = item.matchedPin.pinId,
                    lowUsd = valResult.marketLowUsd,
                    highUsd = valResult.marketHighUsd,
                    confidence = valResult.priceConfidence,
                    scrapperRisk = valResult.scrapperRisk,
                    sourcesJson = citationsJson,
                    referenceImagePath = item.cropImagePath
                ) ?: item.matchedPin
            } else {
                repository.upsertLearnedPin(
                    LearnedPinInput(
                        pinName = valResult.pinName,
                        collection = valResult.collection,
                        subSet = valResult.subSet,
                        resort = valResult.resort,
                        yearWave = valResult.yearWave,
                        rarity = valResult.rarity,
                        marketLowUsd = valResult.marketLowUsd,
                        marketHighUsd = valResult.marketHighUsd,
                        priceConfidence = valResult.priceConfidence,
                        scrapperRisk = valResult.scrapperRisk,
                        visualTags = valResult.visualTags.ifBlank { item.candidate.visualTags },
                        notes = valResult.notes,
                        dataSource = "Gemini Search",
                        referenceImagePath = item.cropImagePath,
                        valuationSourcesJson = citationsJson
                    )
                )
            }

            val updated = item.copy(
                matchedPin = savedPin,
                matchSource = MatchSource.GEMINI_SEARCH,
                matchConfidence = 0.90,
                displayTitle = savedPin.pinName,
                displayCollection = savedPin.collection,
                marketLowUsd = savedPin.marketLowUsd,
                marketHighUsd = savedPin.marketHighUsd,
                estMarketValueUsd = savedPin.estMarketValueUsd,
                priceTier = savedPin.priceTier,
                scrapperRisk = savedPin.scrapperRisk,
                priceConfidence = savedPin.priceConfidence,
                citations = valResult.citations,
                errorNote = null
            )
            val pIdx = updated.photoIndex
            if (pIdx in workingPhotos.indices) {
                val photo = workingPhotos[pIdx]
                workingPhotos[pIdx] = photo.copy(
                    pins = photo.pins.map { if (it.detectionId == updated.detectionId) updated else it }
                )
            }
        }

        emit(
            ScanBatchState(
                isScanning = false,
                statusMessage = "Completed",
                photos = workingPhotos.toList(),
                selectedPhotoIndex = previousState.selectedPhotoIndex,
                selectedDetectionId = previousState.selectedDetectionId,
                errorMessage = null
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun emitAllStages(
        photos: List<Pair<File, Bitmap>>,
        settings: AppSettings
    ): Flow<ScanBatchState> = flow {
        val storedEmbeddings = repository.getAllPinEmbeddings(VisualFingerprint.MODEL_NAME)
        val initialResults = mutableListOf<PhotoScanResult>()

        // Sequential Pass 1 + Local Matching (concurrency = 1)
        for ((photoIdx, pair) in photos.withIndex()) {
            val (file, bitmap) = pair
            emit(
                ScanBatchState(
                    isScanning = true,
                    statusMessage = "Scanning photo ${photoIdx + 1}/${photos.size}...",
                    photos = initialResults.toList()
                )
            )
            val result = runCatching {
                val b64 = ImageUtils.bitmapToJpegBase64(bitmap)
                val detected = geminiClient.detectPinsInPhoto(b64, settings.geminiApiKey, settings.geminiModel)
                val tempDir = File(context.filesDir, "pin_crops_tmp").apply { mkdirs() }
                val mappedPins = detected.map { cand ->
                    val cropFile = File(tempDir, "tmp_${System.currentTimeMillis()}_${photoIdx}_${cand.tempId}.jpg")
                    val cropBmp = ImageUtils.cropAndSavePinThumbnail(bitmap, cand.box2d, cropFile)
                    val cropEmbedding = VisualFingerprint.compute(cropBmp)
                    resolveLocally(
                        photoIndex = photoIdx,
                        candidate = cand,
                        cropFilePath = cropFile.absolutePath,
                        cropEmbedding = cropEmbedding,
                        storedEmbeddings = storedEmbeddings,
                        settings = settings
                    )
                }
                PhotoScanResult(photoIndex = photoIdx, imagePath = file.absolutePath, pins = mappedPins)
            }.getOrElse { err ->
                android.util.Log.e("PinScanOrchestrator", "Pass 1 photo ${photoIdx + 1} failed", err)
                PhotoScanResult(
                    photoIndex = photoIdx,
                    imagePath = file.absolutePath,
                    errorMessage = err.message ?: "Detection failed"
                )
            }
            initialResults.add(result)
        }

        // Select top <= 4 pins for single Pass 2 Search Grounding call; downgrade any others to GEMINI_ESTIMATE
        val workingPhotos = initialResults.toMutableList()
        val allPending = workingPhotos.flatMap { photo ->
            photo.pins.filter { it.matchSource == MatchSource.PENDING_SEARCH }
        }
        val selectedForPass2 = if (settings.autoVerifyOnline) {
            selectTopForVerification(allPending, MAX_AUTO_VERIFY_PINS)
        } else {
            emptyList()
        }
        val selectedIds = selectedForPass2.map { it.detectionId }.toSet()

        // Downgrade non-selected PENDING_SEARCH pins immediately so they keep their Pass 1 estimate
        for (pIdx in workingPhotos.indices) {
            val photo = workingPhotos[pIdx]
            workingPhotos[pIdx] = photo.copy(
                pins = photo.pins.map { det ->
                    if (det.matchSource == MatchSource.PENDING_SEARCH && det.detectionId !in selectedIds) {
                        det.copy(matchSource = downgradedSourceFor(det))
                    } else det
                }
            )
        }

        emit(
            ScanBatchState(
                isScanning = selectedForPass2.isNotEmpty(),
                statusMessage = if (selectedForPass2.isNotEmpty()) {
                    "Verifying top ${selectedForPass2.size} high-value pin(s) online..."
                } else {
                    "Scan complete"
                },
                photos = workingPhotos.toList()
            )
        )

        var pass2ErrorMessage: String? = null
        if (selectedForPass2.isNotEmpty()) {
            val requests = selectedForPass2.mapIndexedNotNull { idx, item ->
                val bmp = ImageUtils.decodeNormalizedFile(item.cropImagePath, maxEdgePx = 192) ?: return@mapIndexedNotNull null
                Pass2CropRequest(
                    tempId = idx,
                    jpegBase64 = ImageUtils.bitmapToJpegBase64(bmp, quality = 75),
                    pass1Hint = "${item.candidate.pinName} ${item.candidate.collectionGuess} ${item.candidate.subSetGuess}".trim(),
                    existingCatalogName = item.matchedPin?.pinName
                )
            }

            val valuationsResult = runCatching {
                geminiClient.researchPinValuations(requests, settings.geminiApiKey, settings.geminiModel)
            }

            if (valuationsResult.isFailure) {
                val err = valuationsResult.exceptionOrNull()
                android.util.Log.e("PinScanOrchestrator", "Pass 2 verification failed", err)
                pass2ErrorMessage = if (err is GeminiRateLimitException) {
                    "Rate limit reached — showing Pass 1 estimates. Tap Refresh on any pin to verify online."
                } else {
                    "${err?.message ?: "Online verification unavailable"} — showing Pass 1 estimates."
                }
                // Circuit breaker: downgrade all remaining PENDING_SEARCH pins cleanly
                for (pIdx in workingPhotos.indices) {
                    val photo = workingPhotos[pIdx]
                    workingPhotos[pIdx] = photo.copy(
                        pins = photo.pins.map { det ->
                            if (det.matchSource == MatchSource.PENDING_SEARCH) {
                                det.copy(matchSource = downgradedSourceFor(det))
                            } else det
                        }
                    )
                }
            } else {
                val valuations = valuationsResult.getOrDefault(emptyList())
                val byTempId = valuations.associateBy { it.tempId }
                selectedForPass2.forEachIndexed { idx, item ->
                    val valResult = byTempId[idx] ?: valuations.getOrNull(idx)
                    val updatedPinResult = if (valResult != null) {
                        val citationsJson = PinSanitizer.encodeCitationsJson(valResult.citations)
                        val savedPin = if (item.matchedPin != null) {
                            repository.updatePinValuation(
                                pinId = item.matchedPin.pinId,
                                lowUsd = valResult.marketLowUsd,
                                highUsd = valResult.marketHighUsd,
                                confidence = valResult.priceConfidence,
                                scrapperRisk = valResult.scrapperRisk,
                                sourcesJson = citationsJson,
                                referenceImagePath = item.cropImagePath
                            ) ?: item.matchedPin
                        } else {
                            repository.upsertLearnedPin(
                                LearnedPinInput(
                                    pinName = valResult.pinName,
                                    collection = valResult.collection,
                                    subSet = valResult.subSet,
                                    resort = valResult.resort,
                                    yearWave = valResult.yearWave,
                                    rarity = valResult.rarity,
                                    marketLowUsd = valResult.marketLowUsd,
                                    marketHighUsd = valResult.marketHighUsd,
                                    priceConfidence = valResult.priceConfidence,
                                    scrapperRisk = valResult.scrapperRisk,
                                    visualTags = valResult.visualTags.ifBlank { item.candidate.visualTags },
                                    notes = valResult.notes,
                                    dataSource = "Gemini Search",
                                    referenceImagePath = "",
                                    valuationSourcesJson = citationsJson
                                )
                            )
                        }

                        val permCropFile = persistPermanentCrop(savedPin.pinId, item.cropImagePath)
                        val cropBmp = ImageUtils.decodeNormalizedFile(permCropFile.absolutePath, maxEdgePx = 320)
                        if (cropBmp != null) {
                            val emb = VisualFingerprint.compute(cropBmp)
                            repository.upsertPinEmbedding(savedPin.pinId, permCropFile.absolutePath, VisualFingerprint.MODEL_NAME, emb)
                        }

                        item.copy(
                            matchedPin = savedPin,
                            cropImagePath = permCropFile.absolutePath,
                            matchSource = MatchSource.GEMINI_SEARCH,
                            matchConfidence = 0.90,
                            displayTitle = savedPin.pinName,
                            displayCollection = savedPin.collection,
                            marketLowUsd = savedPin.marketLowUsd,
                            marketHighUsd = savedPin.marketHighUsd,
                            estMarketValueUsd = savedPin.estMarketValueUsd,
                            priceTier = savedPin.priceTier,
                            scrapperRisk = savedPin.scrapperRisk,
                            priceConfidence = savedPin.priceConfidence,
                            citations = valResult.citations,
                            errorNote = null
                        )
                    } else {
                        item.copy(matchSource = downgradedSourceFor(item))
                    }

                    val pIdx = updatedPinResult.photoIndex
                    val photo = workingPhotos[pIdx]
                    workingPhotos[pIdx] = photo.copy(
                        pins = photo.pins.map { if (it.detectionId == updatedPinResult.detectionId) updatedPinResult else it }
                    )
                }
            }
        }

        // Persist board_scans + scan_detections
        val persistedPhotos = workingPhotos.map { photo ->
            if (photo.pins.isNotEmpty()) {
                val scanId = repository.saveBoardScan(
                    imagePath = photo.imagePath,
                    imageFilename = File(photo.imagePath).name,
                    detections = photo.pins
                )
                photo.copy(scanId = scanId)
            } else photo
        }

        emit(
            ScanBatchState(
                isScanning = false,
                statusMessage = if (pass2ErrorMessage == null) "Completed" else "Completed (Pass 1 estimates)",
                photos = persistedPhotos,
                errorMessage = pass2ErrorMessage ?: persistedPhotos.firstOrNull { it.errorMessage != null }?.errorMessage
            )
        )
    }

    suspend fun refreshSinglePin(pin: Pin, settings: AppSettings): Pin? {
        if (!isOnline() || settings.geminiApiKey.isBlank()) return null
        val bmp = if (pin.referenceImagePath.isNotBlank()) {
            ImageUtils.decodeNormalizedFile(pin.referenceImagePath, maxEdgePx = 192)
        } else null

        val b64 = if (bmp != null) ImageUtils.bitmapToJpegBase64(bmp, 75) else return null
        val valuations = geminiClient.researchPinValuations(
            crops = listOf(
                Pass2CropRequest(
                    tempId = 0,
                    jpegBase64 = b64,
                    pass1Hint = "${pin.pinName} ${pin.collection} ${pin.subSet}",
                    existingCatalogName = pin.pinName
                )
            ),
            apiKey = settings.geminiApiKey,
            model = settings.geminiModel
        )
        val first = valuations.firstOrNull() ?: return null
        val citationsJson = PinSanitizer.encodeCitationsJson(first.citations)
        return repository.updatePinValuation(
            pinId = pin.pinId,
            lowUsd = first.marketLowUsd,
            highUsd = first.marketHighUsd,
            confidence = first.priceConfidence,
            scrapperRisk = first.scrapperRisk,
            sourcesJson = citationsJson
        )
    }

    private suspend fun resolveLocally(
        photoIndex: Int,
        candidate: DetectedPinCandidate,
        cropFilePath: String,
        cropEmbedding: ByteArray,
        storedEmbeddings: List<com.disneypin.valuator.data.model.PinEmbeddingRecord>,
        settings: AppSettings
    ): ScannedPinResult {
        val detId = "p${photoIndex}_t${candidate.tempId}"

        // Pass 0: Visual Fingerprint Cache
        val visualHit = LocalPinMatcher.matchByVisual(cropEmbedding, storedEmbeddings)
        if (visualHit != null) {
            val pin = repository.getPinById(visualHit.first)
            if (pin != null) {
                val fresh = LocalPinMatcher.isPriceFresh(pin.priceUpdated, staleThresholdDays = settings.stalePriceDays)
                val shouldQueueVerify = !fresh && settings.autoVerifyOnline
                return ScannedPinResult(
                    detectionId = detId,
                    photoIndex = photoIndex,
                    box2d = candidate.box2d,
                    cropImagePath = cropFilePath,
                    candidate = candidate,
                    matchedPin = pin,
                    matchSource = if (shouldQueueVerify) MatchSource.PENDING_SEARCH else MatchSource.LOCAL_VISUAL,
                    matchConfidence = visualHit.second,
                    displayTitle = pin.pinName,
                    displayCollection = pin.collection,
                    marketLowUsd = pin.marketLowUsd,
                    marketHighUsd = pin.marketHighUsd,
                    estMarketValueUsd = pin.estMarketValueUsd,
                    priceTier = pin.priceTier,
                    scrapperRisk = pin.scrapperRisk,
                    priceConfidence = pin.priceConfidence,
                    citations = PinSanitizer.parseCitationsJson(pin.valuationSources)
                )
            }
        }

        // FTS + Weighted Metadata Match
        val queries = LocalPinMatcher.buildSearchQueries(candidate)
        val pool = mutableListOf<Pin>()
        for (q in queries) {
            pool.addAll(repository.searchPins(query = q, limit = 25))
        }
        val distinctPool = pool.distinctBy { it.pinId }
        val metaHit = LocalPinMatcher.matchByMetadata(candidate, distinctPool)
        if (metaHit != null) {
            val (pin, score) = metaHit
            val fresh = LocalPinMatcher.isPriceFresh(pin.priceUpdated, staleThresholdDays = settings.stalePriceDays)
            val permFile = persistPermanentCrop(pin.pinId, cropFilePath)
            repository.upsertPinEmbedding(pin.pinId, permFile.absolutePath, VisualFingerprint.MODEL_NAME, cropEmbedding)
            val shouldQueueVerify = !fresh && settings.autoVerifyOnline
            return ScannedPinResult(
                detectionId = detId,
                photoIndex = photoIndex,
                box2d = candidate.box2d,
                cropImagePath = permFile.absolutePath,
                candidate = candidate,
                matchedPin = pin,
                matchSource = if (shouldQueueVerify) MatchSource.PENDING_SEARCH else MatchSource.LOCAL_DB,
                matchConfidence = score,
                displayTitle = pin.pinName,
                displayCollection = pin.collection,
                marketLowUsd = pin.marketLowUsd,
                marketHighUsd = pin.marketHighUsd,
                estMarketValueUsd = pin.estMarketValueUsd,
                priceTier = pin.priceTier,
                scrapperRisk = pin.scrapperRisk,
                priceConfidence = pin.priceConfidence,
                citations = PinSanitizer.parseCitationsJson(pin.valuationSources)
            )
        }

        // Unmatched locally -> immediately persist Pass 1 estimate so it's priced & saved offline!
        val shouldVerify = candidate.needsWebVerification ||
            candidate.estHighUsd >= 25.0 ||
            candidate.rarityHint != "Common" ||
            candidate.confidence < 0.70

        val savedEstimatePin = repository.upsertLearnedPin(
            LearnedPinInput(
                pinName = candidate.pinName,
                collection = candidate.collectionGuess.ifBlank { "Disney Parks" },
                subSet = candidate.subSetGuess,
                resort = candidate.resortGuess,
                rarity = candidate.rarityHint,
                marketLowUsd = candidate.estLowUsd,
                marketHighUsd = candidate.estHighUsd,
                priceConfidence = "Peer Est.",
                scrapperRisk = candidate.scrapperRiskHint,
                visualTags = candidate.visualTags,
                notes = candidate.scrapperNotes,
                dataSource = "Gemini Estimate"
            )
        )
        val permFile = persistPermanentCrop(savedEstimatePin.pinId, cropFilePath)
        repository.upsertPinEmbedding(savedEstimatePin.pinId, permFile.absolutePath, VisualFingerprint.MODEL_NAME, cropEmbedding)

        return ScannedPinResult(
            detectionId = detId,
            photoIndex = photoIndex,
            box2d = candidate.box2d,
            cropImagePath = permFile.absolutePath,
            candidate = candidate,
            matchedPin = savedEstimatePin,
            matchSource = if (shouldVerify && settings.autoVerifyOnline) MatchSource.PENDING_SEARCH else MatchSource.GEMINI_ESTIMATE,
            matchConfidence = candidate.confidence,
            displayTitle = savedEstimatePin.pinName,
            displayCollection = savedEstimatePin.collection,
            marketLowUsd = savedEstimatePin.marketLowUsd,
            marketHighUsd = savedEstimatePin.marketHighUsd,
            estMarketValueUsd = savedEstimatePin.estMarketValueUsd,
            priceTier = savedEstimatePin.priceTier,
            scrapperRisk = savedEstimatePin.scrapperRisk,
            priceConfidence = savedEstimatePin.priceConfidence
        )
    }

    private fun downgradedSourceFor(det: ScannedPinResult): MatchSource {
        return if (det.matchedPin != null && det.matchedPin.dataSource == "Seeded") {
            MatchSource.LOCAL_DB
        } else {
            MatchSource.GEMINI_ESTIMATE
        }
    }

    private fun persistPermanentCrop(pinId: String, tempPath: String): File {
        val dir = File(context.filesDir, "pin_crops").apply { mkdirs() }
        val target = File(dir, "$pinId.jpg")
        val src = File(tempPath)
        if (src.exists() && src.absolutePath != target.absolutePath) {
            src.copyTo(target, overwrite = true)
        }
        return target
    }

    companion object {
        const val MAX_AUTO_VERIFY_PINS = 4

        fun selectTopForVerification(
            items: List<ScannedPinResult>,
            maxPins: Int = MAX_AUTO_VERIFY_PINS
        ): List<ScannedPinResult> {
            return items
                .sortedWith(
                    compareByDescending<ScannedPinResult> { it.marketHighUsd }
                        .thenBy { it.candidate.confidence }
                )
                .take(maxPins.coerceAtLeast(0))
        }
    }
}
