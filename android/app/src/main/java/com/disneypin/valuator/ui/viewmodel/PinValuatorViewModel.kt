package com.disneypin.valuator.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.disneypin.valuator.data.db.PinDatabaseHelper
import com.disneypin.valuator.data.db.PinRepository
import com.disneypin.valuator.data.model.AppSettings
import com.disneypin.valuator.data.model.AppraisalResult
import com.disneypin.valuator.data.model.BackstampStatus
import com.disneypin.valuator.data.model.BoardScanSummary
import com.disneypin.valuator.data.model.CatalogStats
import com.disneypin.valuator.data.model.EnamelQualityStatus
import com.disneypin.valuator.data.model.PendingScanItem
import com.disneypin.valuator.data.model.PhotoScanResult
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PortfolioItem
import com.disneypin.valuator.data.model.PriceHistoryEntry
import com.disneypin.valuator.data.model.ScanBatchState
import com.disneypin.valuator.data.model.ScrapperAssessmentInput
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeListType
import com.disneypin.valuator.data.model.TradeMatch
import com.disneypin.valuator.data.model.WafflePatternStatus
import com.disneypin.valuator.data.remote.GeminiApiClient
import com.disneypin.valuator.data.settings.SettingsRepository
import com.disneypin.valuator.domain.EvaluationEngine
import com.disneypin.valuator.domain.PinScanOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PinValuatorViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = PinDatabaseHelper(application.applicationContext)
    private val repository = PinRepository(dbHelper)
    private val settingsRepository = SettingsRepository(application.applicationContext)
    private val geminiClient = GeminiApiClient()
    private val orchestrator = PinScanOrchestrator(
        context = application.applicationContext,
        repository = repository,
        geminiClient = geminiClient
    )

    private val _settingsState = MutableStateFlow(settingsRepository.loadSettings())
    val settingsState: StateFlow<AppSettings> = _settingsState.asStateFlow()

    private val _isOnline = MutableStateFlow(orchestrator.isOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _scanBatchState = MutableStateFlow(ScanBatchState())
    val scanBatchState: StateFlow<ScanBatchState> = _scanBatchState.asStateFlow()

    private val _recentScans = MutableStateFlow<List<BoardScanSummary>>(emptyList())
    val recentScans: StateFlow<List<BoardScanSummary>> = _recentScans.asStateFlow()

    private val _pendingScans = MutableStateFlow<List<PendingScanItem>>(emptyList())
    val pendingScans: StateFlow<List<PendingScanItem>> = _pendingScans.asStateFlow()

    private val _catalogStats = MutableStateFlow<CatalogStats?>(null)
    val catalogStats: StateFlow<CatalogStats?> = _catalogStats.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedPriceTier = MutableStateFlow("All")
    val selectedPriceTier: StateFlow<String> = _selectedPriceTier.asStateFlow()

    private val _selectedRarity = MutableStateFlow("All")
    val selectedRarity: StateFlow<String> = _selectedRarity.asStateFlow()

    private val _selectedResort = MutableStateFlow("All")
    val selectedResort: StateFlow<String> = _selectedResort.asStateFlow()

    private val _selectedSourceFilter = MutableStateFlow("All")
    val selectedSourceFilter: StateFlow<String> = _selectedSourceFilter.asStateFlow()

    private val _catalogPins = MutableStateFlow<List<Pin>>(emptyList())
    val catalogPins: StateFlow<List<Pin>> = _catalogPins.asStateFlow()

    private val _isLoadingCatalog = MutableStateFlow(true)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    private val _selectedPin = MutableStateFlow<Pin?>(null)
    val selectedPin: StateFlow<Pin?> = _selectedPin.asStateFlow()

    private val _selectedPinHistory = MutableStateFlow<List<PriceHistoryEntry>>(emptyList())
    val selectedPinHistory: StateFlow<List<PriceHistoryEntry>> = _selectedPinHistory.asStateFlow()

    private val _isRefreshingPin = MutableStateFlow(false)
    val isRefreshingPin: StateFlow<Boolean> = _isRefreshingPin.asStateFlow()

    private val _portfolioItems = MutableStateFlow<List<PortfolioItem>>(emptyList())
    val portfolioItems: StateFlow<List<PortfolioItem>> = _portfolioItems.asStateFlow()

    private val _traders = MutableStateFlow<List<TradeListEntry>>(emptyList())
    val traders: StateFlow<List<TradeListEntry>> = _traders.asStateFlow()

    private val _isoList = MutableStateFlow<List<TradeListEntry>>(emptyList())
    val isoList: StateFlow<List<TradeListEntry>> = _isoList.asStateFlow()

    private val _tradeMatches = MutableStateFlow<List<TradeMatch>>(emptyList())
    val tradeMatches: StateFlow<List<TradeMatch>> = _tradeMatches.asStateFlow()

    // Manual Appraisal (optional secondary flow)
    private val _scannerPin = MutableStateFlow<Pin?>(null)
    val scannerPin: StateFlow<Pin?> = _scannerPin.asStateFlow()

    private val _scrapperInput = MutableStateFlow(ScrapperAssessmentInput())
    val scrapperInput: StateFlow<ScrapperAssessmentInput> = _scrapperInput.asStateFlow()

    private val _conditionGrade = MutableStateFlow(9)
    val conditionGrade: StateFlow<Int> = _conditionGrade.asStateFlow()

    private val _hasOriginalCard = MutableStateFlow(false)
    val hasOriginalCard: StateFlow<Boolean> = _hasOriginalCard.asStateFlow()

    private val _customBaseValueUsd = MutableStateFlow(25.0)
    val customBaseValueUsd: StateFlow<Double> = _customBaseValueUsd.asStateFlow()

    private val _appraisalResult = MutableStateFlow<AppraisalResult?>(null)
    val appraisalResult: StateFlow<AppraisalResult?> = _appraisalResult.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        loadInitialData()
        recalculateAppraisal()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _isOnline.value = orchestrator.isOnline()
            _catalogStats.value = repository.getCatalogStats()
            refreshCatalog()
            refreshPortfolio()
            refreshTrades()
            refreshScanHistory()
        }
    }

    fun scanPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _isOnline.value = orchestrator.isOnline()
        viewModelScope.launch {
            orchestrator.scanPhotoUris(uris, _settingsState.value).collect { state ->
                _scanBatchState.value = state
                if (!state.isScanning) {
                    _catalogStats.value = repository.getCatalogStats()
                    refreshCatalog()
                    refreshScanHistory()
                }
            }
        }
    }

    fun processPendingQueue() {
        _isOnline.value = orchestrator.isOnline()
        viewModelScope.launch {
            val items = _pendingScans.value.map { it.id to it.imagePath }
            if (items.isEmpty()) return@launch
            orchestrator.processPendingFiles(items, _settingsState.value).collect { state ->
                _scanBatchState.value = state
                if (!state.isScanning) {
                    _catalogStats.value = repository.getCatalogStats()
                    refreshCatalog()
                    refreshScanHistory()
                }
            }
        }
    }

    fun retryCurrentScan() {
        _isOnline.value = orchestrator.isOnline()
        val current = _scanBatchState.value
        viewModelScope.launch {
            orchestrator.retryScanBatch(current, _settingsState.value).collect { state ->
                _scanBatchState.value = state
                if (!state.isScanning) {
                    _catalogStats.value = repository.getCatalogStats()
                    refreshCatalog()
                    refreshScanHistory()
                }
            }
        }
    }

    fun openRecentScan(scanSummary: BoardScanSummary) {
        viewModelScope.launch {
            val detections = repository.getScanDetections(scanSummary.scanId)
            _scanBatchState.value = ScanBatchState(
                isScanning = false,
                statusMessage = "Loaded saved scan (${detections.size} pins)",
                photos = listOf(
                    PhotoScanResult(
                        photoIndex = 0,
                        imagePath = scanSummary.imagePath,
                        scanId = scanSummary.scanId,
                        pins = detections
                    )
                ),
                selectedPhotoIndex = 0
            )
        }
    }

    fun selectScanPhoto(index: Int) {
        _scanBatchState.value = _scanBatchState.value.copy(selectedPhotoIndex = index)
    }

    fun selectScanDetection(detectionId: String?) {
        _scanBatchState.value = _scanBatchState.value.copy(selectedDetectionId = detectionId)
    }

    fun refreshPinOnline(pin: Pin) {
        viewModelScope.launch {
            _isRefreshingPin.value = true
            val updated = orchestrator.refreshSinglePin(pin, _settingsState.value)
            _isRefreshingPin.value = false
            if (updated != null) {
                if (_selectedPin.value?.pinId == updated.pinId) {
                    _selectedPin.value = updated
                    _selectedPinHistory.value = repository.getPriceHistory(updated.pinId)
                }
                refreshCatalog()
                _catalogStats.value = repository.getCatalogStats()
                _snackbarMessage.value = "Updated ${updated.pinName}: $${updated.marketLowUsd.toInt()}-$${updated.marketHighUsd.toInt()}"
            } else {
                _snackbarMessage.value = "Could not refresh price online (check internet/API key or crop image)"
            }
        }
    }

    fun updatePinManually(
        pinId: String,
        pinName: String,
        collection: String,
        lowUsd: Double,
        highUsd: Double,
        rarity: String,
        scrapperRisk: String
    ) {
        viewModelScope.launch {
            val updated = repository.updatePinManually(
                pinId = pinId,
                pinName = pinName,
                collection = collection,
                lowUsd = lowUsd,
                highUsd = highUsd,
                rarity = rarity,
                scrapperRisk = scrapperRisk
            )
            if (updated != null) {
                if (_selectedPin.value?.pinId == updated.pinId) {
                    _selectedPin.value = updated
                    _selectedPinHistory.value = repository.getPriceHistory(updated.pinId)
                }
                // Update in active scanBatchState if present
                val currentPhotos = _scanBatchState.value.photos.map { photo ->
                    photo.copy(
                        pins = photo.pins.map { sp ->
                            if (sp.matchedPin?.pinId == updated.pinId) {
                                sp.copy(
                                    matchedPin = updated,
                                    displayTitle = updated.pinName,
                                    displayCollection = updated.collection,
                                    marketLowUsd = updated.marketLowUsd,
                                    marketHighUsd = updated.marketHighUsd,
                                    estMarketValueUsd = updated.estMarketValueUsd,
                                    priceTier = updated.priceTier,
                                    scrapperRisk = updated.scrapperRisk
                                )
                            } else sp
                        }
                    )
                }
                _scanBatchState.value = _scanBatchState.value.copy(photos = currentPhotos)
                refreshCatalog()
                _snackbarMessage.value = "Saved changes to ${updated.pinName}"
            }
        }
    }

    fun saveSettings(apiKey: String, model: String, staleDays: Int, autoVerifyOnline: Boolean) {
        settingsRepository.saveSettings(apiKey, model, staleDays, autoVerifyOnline)
        _settingsState.value = settingsRepository.loadSettings()
        _snackbarMessage.value = "Settings saved"
    }

    suspend fun testGeminiConnection(apiKey: String, model: String): Result<String> {
        return geminiClient.testConnection(apiKey, model)
    }

    private suspend fun refreshScanHistory() {
        _recentScans.value = repository.getRecentBoardScans()
        _pendingScans.value = repository.getPendingScans()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        refreshCatalog()
    }

    fun updatePriceTierFilter(tier: String) {
        _selectedPriceTier.value = tier
        refreshCatalog()
    }

    fun updateRarityFilter(rarity: String) {
        _selectedRarity.value = rarity
        refreshCatalog()
    }

    fun updateSourceFilter(source: String) {
        _selectedSourceFilter.value = source
        refreshCatalog()
    }

    private fun refreshCatalog() {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            _catalogPins.value = repository.searchPins(
                query = _searchQuery.value,
                priceTierFilter = _selectedPriceTier.value,
                rarityFilter = _selectedRarity.value,
                resortFilter = _selectedResort.value,
                sourceFilter = _selectedSourceFilter.value,
                limit = 150
            )
            _isLoadingCatalog.value = false
        }
    }

    fun selectPin(pin: Pin) {
        _selectedPin.value = pin
        viewModelScope.launch {
            _selectedPinHistory.value = repository.getPriceHistory(pin.pinId)
        }
    }

    fun startEvaluationForPin(pin: Pin) {
        _scannerPin.value = pin
        _customBaseValueUsd.value = pin.estMarketValueUsd
        _scrapperInput.value = _scrapperInput.value.copy(baselineCatalogRisk = pin.scrapperRisk)
        recalculateAppraisal()
    }

    fun clearScannerPin() {
        _scannerPin.value = null
        _scrapperInput.value = _scrapperInput.value.copy(baselineCatalogRisk = "Low")
        recalculateAppraisal()
    }

    fun updateWafflePattern(status: WafflePatternStatus) {
        _scrapperInput.value = _scrapperInput.value.copy(wafflePattern = status)
        recalculateAppraisal()
    }

    fun updateEnamelQuality(status: EnamelQualityStatus) {
        _scrapperInput.value = _scrapperInput.value.copy(enamelQuality = status)
        recalculateAppraisal()
    }

    fun updateBackstampStatus(status: BackstampStatus) {
        _scrapperInput.value = _scrapperInput.value.copy(backstampStatus = status)
        recalculateAppraisal()
    }

    fun updateWeightGrams(text: String) {
        _scrapperInput.value = _scrapperInput.value.copy(weightGrams = text.toDoubleOrNull())
        recalculateAppraisal()
    }

    fun updateThicknessMm(text: String) {
        _scrapperInput.value = _scrapperInput.value.copy(thicknessMm = text.toDoubleOrNull())
        recalculateAppraisal()
    }

    fun updateIsMagnetic(magnetic: Boolean) {
        _scrapperInput.value = _scrapperInput.value.copy(isMagnetic = magnetic)
        recalculateAppraisal()
    }

    fun updateOcrText(text: String) {
        _scrapperInput.value = _scrapperInput.value.copy(ocrBackstampText = text)
        recalculateAppraisal()
    }

    fun updateConditionGrade(grade: Int) {
        _conditionGrade.value = grade.coerceIn(1, 10)
        recalculateAppraisal()
    }

    fun updateHasOriginalCard(hasCard: Boolean) {
        _hasOriginalCard.value = hasCard
        recalculateAppraisal()
    }

    fun updateCustomBaseValue(valueText: String) {
        val parsed = valueText.toDoubleOrNull()
        if (parsed != null && parsed >= 0.0) {
            _customBaseValueUsd.value = parsed
            recalculateAppraisal()
        }
    }

    private fun recalculateAppraisal() {
        val pin = _scannerPin.value
        val baseVal = pin?.estMarketValueUsd ?: _customBaseValueUsd.value
        val rarity = pin?.rarity ?: "Common"
        _appraisalResult.value = EvaluationEngine.appraisePin(
            baseFmvUsd = baseVal,
            conditionGrade = _conditionGrade.value,
            hasOriginalCard = _hasOriginalCard.value,
            scrapperInput = _scrapperInput.value,
            rarity = rarity
        )
    }

    fun addPinToPortfolio(
        pin: Pin,
        purchasePriceUsd: Double = pin.estMarketValueUsd,
        conditionGrade: Int = 8,
        hasOriginalCard: Boolean = false,
        notes: String = ""
    ) {
        viewModelScope.launch {
            repository.addToPortfolio(pin.pinId, purchasePriceUsd, conditionGrade, hasOriginalCard, notes)
            refreshPortfolio()
            _snackbarMessage.value = "Added \"${pin.pinName}\" to Portfolio"
        }
    }

    fun removePortfolioItem(itemId: Long) {
        viewModelScope.launch {
            repository.removeFromPortfolio(itemId)
            refreshPortfolio()
            _snackbarMessage.value = "Removed from Portfolio"
        }
    }

    private suspend fun refreshPortfolio() {
        _portfolioItems.value = repository.getPortfolioItems()
    }

    fun addPinToTradeList(pin: Pin, type: TradeListType, grade: Int = 8) {
        viewModelScope.launch {
            repository.addToTradeList(pin.pinId, type, grade)
            refreshTrades()
            val label = if (type == TradeListType.TRADER) "Traders" else "ISO list"
            _snackbarMessage.value = "Added \"${pin.pinName}\" to $label"
        }
    }

    fun removeTradeEntry(entryId: Long) {
        viewModelScope.launch {
            repository.removeFromTradeList(entryId)
            refreshTrades()
        }
    }

    private suspend fun refreshTrades() {
        val traderItems = repository.getTradeList(TradeListType.TRADER)
        val isoItems = repository.getTradeList(TradeListType.ISO)
        _traders.value = traderItems
        _isoList.value = isoItems
        _tradeMatches.value = EvaluationEngine.matchTrades(
            traders = traderItems,
            isoPins = isoItems.map { it.pin }
        )
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        dbHelper.close()
    }
}
