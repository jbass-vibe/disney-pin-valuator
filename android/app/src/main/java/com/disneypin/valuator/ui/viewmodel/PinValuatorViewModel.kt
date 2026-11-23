package com.disneypin.valuator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.disneypin.valuator.data.db.PinDatabaseHelper
import com.disneypin.valuator.data.db.PinRepository
import com.disneypin.valuator.data.model.AppraisalResult
import com.disneypin.valuator.data.model.BackstampStatus
import com.disneypin.valuator.data.model.CatalogStats
import com.disneypin.valuator.data.model.EnamelQualityStatus
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PortfolioItem
import com.disneypin.valuator.data.model.PriceHistoryEntry
import com.disneypin.valuator.data.model.ScrapperAssessmentInput
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeListType
import com.disneypin.valuator.data.model.TradeMatch
import com.disneypin.valuator.data.model.WafflePatternStatus
import com.disneypin.valuator.domain.EvaluationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PinValuatorViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = PinDatabaseHelper(application.applicationContext)
    private val repository = PinRepository(dbHelper)

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

    private val _catalogPins = MutableStateFlow<List<Pin>>(emptyList())
    val catalogPins: StateFlow<List<Pin>> = _catalogPins.asStateFlow()

    private val _isLoadingCatalog = MutableStateFlow(true)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    private val _selectedPin = MutableStateFlow<Pin?>(null)
    val selectedPin: StateFlow<Pin?> = _selectedPin.asStateFlow()

    private val _selectedPinHistory = MutableStateFlow<List<PriceHistoryEntry>>(emptyList())
    val selectedPinHistory: StateFlow<List<PriceHistoryEntry>> = _selectedPinHistory.asStateFlow()

    private val _portfolioItems = MutableStateFlow<List<PortfolioItem>>(emptyList())
    val portfolioItems: StateFlow<List<PortfolioItem>> = _portfolioItems.asStateFlow()

    private val _traders = MutableStateFlow<List<TradeListEntry>>(emptyList())
    val traders: StateFlow<List<TradeListEntry>> = _traders.asStateFlow()

    private val _isoList = MutableStateFlow<List<TradeListEntry>>(emptyList())
    val isoList: StateFlow<List<TradeListEntry>> = _isoList.asStateFlow()

    private val _tradeMatches = MutableStateFlow<List<TradeMatch>>(emptyList())
    val tradeMatches: StateFlow<List<TradeMatch>> = _tradeMatches.asStateFlow()

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
            _catalogStats.value = repository.getCatalogStats()
            refreshCatalog()
            refreshPortfolio()
            refreshTrades()
        }
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

    fun updateResortFilter(resort: String) {
        _selectedResort.value = resort
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
        _scrapperInput.value = _scrapperInput.value.copy(
            baselineCatalogRisk = pin.scrapperRisk
        )
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
        val parsed = text.toDoubleOrNull()
        _scrapperInput.value = _scrapperInput.value.copy(weightGrams = parsed)
        recalculateAppraisal()
    }

    fun updateThicknessMm(text: String) {
        val parsed = text.toDoubleOrNull()
        _scrapperInput.value = _scrapperInput.value.copy(thicknessMm = parsed)
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
            repository.addToPortfolio(
                pinId = pin.pinId,
                purchasePriceUsd = purchasePriceUsd,
                conditionGrade = conditionGrade,
                hasOriginalCard = hasOriginalCard,
                notes = notes
            )
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
