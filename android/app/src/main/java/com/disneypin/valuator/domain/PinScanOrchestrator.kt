package com.disneypin.valuator.domain

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.disneypin.valuator.data.db.PinRepository
import com.disneypin.valuator.data.db.PinSanitizer
import com.disneypin.valuator.data.model.AppSettings
import com.disneypin.valuator.data.model.BoundingBoxNorm
import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.LearnedPinInput
import com.disneypin.valuator.data.model.MatchSource
import com.disneypin.valuator.data.model.PhotoScanResult
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.ScanBatchState
import com.disneypin.valuator.data.model.ScannedPinResult
import com.disneypin.valuator.data.remote.GeminiApiClient
import com.disneypin.valuator.data.remote.Pass2CropRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private fun emitAllStages(
        photos: List<Pair<File, Bitmap>>,
        settings: AppSettings
    ): Flow<ScanBatchState> = flow {
        emit(
            ScanBatchState(
                isScanning = true,
                statusMessage = "Detecting pins in ${photos.size} photo(s)..."
            )
        )

        val storedEmbeddings = repository.getAllPinEmbeddings(VisualFingerprint.MODEL_NAME)
        val semaphore = Semaphore(2)

        // Pass 1 + Local Matching
        val initialResults: List<PhotoScanResult> = coroutineScope {
            photos.mapIndexed { photoIdx, (file, bitmap) ->
                async {
                    semaphore.withPermit {
                        runCatching {
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
                                    staleThresholdDays = settings.stalePriceDays
                                )
                            }
                            PhotoScanResult(photoIndex = photoIdx, imagePath = file.absolutePath, pins = mappedPins)
                        }.getOrElse { err ->
                            PhotoScanResult(
                                photoIndex = photoIdx,
                                imagePath = file.absolutePath,
                                errorMessage = err.message ?: "Detection failed"
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val unresolvedCount = initialResults.sumOf { p -> p.pins.count { it.matchSource == MatchSource.PENDING_SEARCH } }
        emit(
            ScanBatchState(
                isScanning = unresolvedCount > 0,
                statusMessage = if (unresolvedCount > 0) "Searching market prices for $unresolvedCount pin(s)..." else "Scan complete",
                photos = initialResults
            )
        )

        // Pass 2 for unresolved / stale pins in chunks of <= 5
        val workingPhotos = initialResults.toMutableList()
        val pendingItems = workingPhotos.flatMap { photo ->
            photo.pins.filter { it.matchSource == MatchSource.PENDING_SEARCH }
        }

        if (pendingItems.isNotEmpty()) {
            val chunks = pendingItems.chunked(5)
            chunks.forEachIndexed { chunkIdx, chunk ->
                emit(
                    ScanBatchState(
                        isScanning = true,
                        statusMessage = "Researching market prices (batch ${chunkIdx + 1}/${chunks.size})...",
                        photos = workingPhotos.toList()
                    )
                )
                val requests = chunk.mapIndexedNotNull { idx, item ->
                    val bmp = ImageUtils.decodeNormalizedFile(item.cropImagePath, maxEdgePx = 320) ?: return@mapIndexedNotNull null
                    Pass2CropRequest(
                        tempId = idx,
                        jpegBase64 = ImageUtils.bitmapToJpegBase64(bmp, quality = 85),
                        pass1Hint = "${item.candidate.pinName} ${item.candidate.collectionGuess} ${item.candidate.subSetGuess}".trim(),
                        existingCatalogName = item.matchedPin?.pinName
                    )
                }

                val valuations = runCatching {
                    geminiClient.researchPinValuations(requests, settings.geminiApiKey, settings.geminiModel)
                }.getOrElse { emptyList() }

                val byTempId = valuations.associateBy { it.tempId }
                chunk.forEachIndexed { idx, item ->
                    val valResult = byTempId[idx]
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
                                    referenceImagePath = "",
                                    valuationSourcesJson = citationsJson
                                )
                            )
                        }

                        // Persist permanent crop + visual fingerprint
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
                            citations = valResult.citations
                        )
                    } else {
                        item.copy(
                            matchSource = if (item.matchedPin != null) MatchSource.LOCAL_DB else MatchSource.GEMINI_SEARCH,
                            errorNote = "Could not verify price online"
                        )
                    }

                    // Replace in workingPhotos
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
                statusMessage = "Completed",
                photos = persistedPhotos,
                errorMessage = persistedPhotos.firstOrNull { it.errorMessage != null }?.errorMessage
            )
        )
    }

    suspend fun refreshSinglePin(pin: Pin, settings: AppSettings): Pin? {
        if (!isOnline() || settings.geminiApiKey.isBlank()) return null
        val bmp = if (pin.referenceImagePath.isNotBlank()) {
            ImageUtils.decodeNormalizedFile(pin.referenceImagePath, maxEdgePx = 320)
        } else null

        val b64 = if (bmp != null) ImageUtils.bitmapToJpegBase64(bmp, 85) else return null
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
        staleThresholdDays: Int
    ): ScannedPinResult {
        val detId = "p${photoIndex}_t${candidate.tempId}"

        // Pass 0: Visual Fingerprint Cache
        val visualHit = LocalPinMatcher.matchByVisual(cropEmbedding, storedEmbeddings)
        if (visualHit != null) {
            val pin = repository.getPinById(visualHit.first)
            if (pin != null) {
                val fresh = LocalPinMatcher.isPriceFresh(pin.priceUpdated, staleThresholdDays = staleThresholdDays)
                return ScannedPinResult(
                    detectionId = detId,
                    photoIndex = photoIndex,
                    box2d = candidate.box2d,
                    cropImagePath = cropFilePath,
                    candidate = candidate,
                    matchedPin = pin,
                    matchSource = if (fresh) MatchSource.LOCAL_VISUAL else MatchSource.PENDING_SEARCH,
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

        // FTS5 + Weighted Metadata Match
        val queries = LocalPinMatcher.buildSearchQueries(candidate)
        val pool = mutableListOf<Pin>()
        for (q in queries) {
            pool.addAll(repository.searchPins(query = q, limit = 25))
        }
        val distinctPool = pool.distinctBy { it.pinId }
        val metaHit = LocalPinMatcher.matchByMetadata(candidate, distinctPool)
        if (metaHit != null) {
            val (pin, score) = metaHit
            val fresh = LocalPinMatcher.isPriceFresh(pin.priceUpdated, staleThresholdDays = staleThresholdDays)
            if (fresh) {
                // Also cache visual embedding for future Pass 0 hits
                val permFile = persistPermanentCrop(pin.pinId, cropFilePath)
                repository.upsertPinEmbedding(pin.pinId, permFile.absolutePath, VisualFingerprint.MODEL_NAME, cropEmbedding)
                return ScannedPinResult(
                    detectionId = detId,
                    photoIndex = photoIndex,
                    box2d = candidate.box2d,
                    cropImagePath = permFile.absolutePath,
                    candidate = candidate,
                    matchedPin = pin,
                    matchSource = MatchSource.LOCAL_DB,
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
            return ScannedPinResult(
                detectionId = detId,
                photoIndex = photoIndex,
                box2d = candidate.box2d,
                cropImagePath = cropFilePath,
                candidate = candidate,
                matchedPin = pin,
                matchSource = MatchSource.PENDING_SEARCH,
                matchConfidence = score,
                displayTitle = pin.pinName,
                displayCollection = pin.collection,
                marketLowUsd = pin.marketLowUsd,
                marketHighUsd = pin.marketHighUsd,
                estMarketValueUsd = pin.estMarketValueUsd,
                priceTier = pin.priceTier,
                scrapperRisk = pin.scrapperRisk,
                priceConfidence = pin.priceConfidence
            )
        }

        return ScannedPinResult(
            detectionId = detId,
            photoIndex = photoIndex,
            box2d = candidate.box2d,
            cropImagePath = cropFilePath,
            candidate = candidate,
            matchSource = MatchSource.PENDING_SEARCH,
            matchConfidence = candidate.confidence
        )
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
}
