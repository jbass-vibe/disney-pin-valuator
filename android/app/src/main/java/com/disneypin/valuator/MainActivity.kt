package com.disneypin.valuator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.disneypin.valuator.data.model.TradeListType
import com.disneypin.valuator.ui.screens.CatalogScreen
import com.disneypin.valuator.ui.screens.ManualAppraisalScreen
import com.disneypin.valuator.ui.screens.MyPinsScreen
import com.disneypin.valuator.ui.screens.PinDetailScreen
import com.disneypin.valuator.ui.screens.ScanHomeScreen
import com.disneypin.valuator.ui.screens.ScanResultScreen
import com.disneypin.valuator.ui.screens.SettingsDialog
import com.disneypin.valuator.ui.theme.DisneyPinValuatorTheme
import com.disneypin.valuator.ui.viewmodel.PinValuatorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DisneyPinValuatorTheme {
                DisneyPinValuatorApp()
            }
        }
    }
}

private enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    SCAN("scan", "Scan", Icons.Default.CameraAlt),
    CATALOG("catalog", "Catalog", Icons.Default.Search),
    MY_PINS("my_pins", "My Pins", Icons.Default.CollectionsBookmark)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisneyPinValuatorApp(viewModel: PinValuatorViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val settingsState by viewModel.settingsState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val scanBatchState by viewModel.scanBatchState.collectAsState()
    val recentScans by viewModel.recentScans.collectAsState()
    val pendingScans by viewModel.pendingScans.collectAsState()

    val catalogStats by viewModel.catalogStats.collectAsState()
    val catalogPins by viewModel.catalogPins.collectAsState()
    val isLoadingCatalog by viewModel.isLoadingCatalog.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedPriceTier by viewModel.selectedPriceTier.collectAsState()
    val selectedRarity by viewModel.selectedRarity.collectAsState()
    val selectedSourceFilter by viewModel.selectedSourceFilter.collectAsState()

    val selectedPin by viewModel.selectedPin.collectAsState()
    val selectedPinHistory by viewModel.selectedPinHistory.collectAsState()
    val isRefreshingPin by viewModel.isRefreshingPin.collectAsState()

    val portfolioItems by viewModel.portfolioItems.collectAsState()
    val traders by viewModel.traders.collectAsState()
    val isoList by viewModel.isoList.collectAsState()
    val tradeMatches by viewModel.tradeMatches.collectAsState()

    val scannerPin by viewModel.scannerPin.collectAsState()
    val scrapperInput by viewModel.scrapperInput.collectAsState()
    val conditionGrade by viewModel.conditionGrade.collectAsState()
    val hasOriginalCard by viewModel.hasOriginalCard.collectAsState()
    val customBaseValueUsd by viewModel.customBaseValueUsd.collectAsState()
    val appraisalResult by viewModel.appraisalResult.collectAsState()

    if (showSettingsDialog) {
        SettingsDialog(
            settings = settingsState,
            stats = catalogStats,
            onDismiss = { showSettingsDialog = false },
            onSave = viewModel::saveSettings,
            onTestConnection = viewModel::testGeminiConnection
        )
    }

    val showBottomBar = currentRoute in setOf(
        BottomTab.SCAN.route,
        BottomTab.CATALOG.route,
        BottomTab.MY_PINS.route
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showBottomBar) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Disney Pin Valuator",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(BottomTab.SCAN.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomTab.SCAN.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomTab.SCAN.route) {
                ScanHomeScreen(
                    stats = catalogStats,
                    hasApiKey = settingsState.geminiApiKey.isNotBlank(),
                    isOnline = isOnline,
                    pendingScans = pendingScans,
                    recentScans = recentScans,
                    onScanUris = { uris ->
                        viewModel.scanPhotos(uris)
                        navController.navigate("scan_result")
                    },
                    onProcessPendingQueue = {
                        viewModel.processPendingQueue()
                        navController.navigate("scan_result")
                    },
                    onOpenRecentScan = { summary ->
                        viewModel.openRecentScan(summary)
                        navController.navigate("scan_result")
                    },
                    onOpenSettings = { showSettingsDialog = true }
                )
            }

            composable("scan_result") {
                ScanResultScreen(
                    batchState = scanBatchState,
                    onBack = { navController.popBackStack() },
                    onRetryScan = viewModel::retryCurrentScan,
                    onSelectDetection = viewModel::selectScanDetection,
                    onOpenPinDetail = { pin ->
                        viewModel.selectPin(pin)
                        navController.navigate("detail")
                    },
                    onRefreshPin = viewModel::refreshPinOnline,
                    onSaveToPortfolio = { pin -> viewModel.addPinToPortfolio(pin) },
                    onEditPin = { pin, name, col, low, high, rarity, risk ->
                        viewModel.updatePinManually(pin.pinId, name, col, low, high, rarity, risk)
                    }
                )
            }

            composable(BottomTab.CATALOG.route) {
                CatalogScreen(
                    stats = catalogStats,
                    pins = catalogPins,
                    isLoading = isLoadingCatalog,
                    searchQuery = searchQuery,
                    selectedPriceTier = selectedPriceTier,
                    selectedRarity = selectedRarity,
                    selectedSourceFilter = selectedSourceFilter,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onPriceTierChange = viewModel::updatePriceTierFilter,
                    onRarityChange = viewModel::updateRarityFilter,
                    onSourceFilterChange = viewModel::updateSourceFilter,
                    onPinClick = { pin ->
                        viewModel.selectPin(pin)
                        navController.navigate("detail")
                    }
                )
            }

            composable("detail") {
                selectedPin?.let { pin ->
                    PinDetailScreen(
                        pin = pin,
                        priceHistory = selectedPinHistory,
                        isRefreshing = isRefreshingPin,
                        onBack = { navController.popBackStack() },
                        onRefreshOnline = viewModel::refreshPinOnline,
                        onEditPin = { p, name, col, low, high, rarity, risk ->
                            viewModel.updatePinManually(p.pinId, name, col, low, high, rarity, risk)
                        },
                        onAddToPortfolio = { viewModel.addPinToPortfolio(it) },
                        onAddToTradeList = { p, type -> viewModel.addPinToTradeList(p, type) },
                        onEvaluatePin = { p ->
                            viewModel.startEvaluationForPin(p)
                            navController.navigate("manual_appraisal")
                        }
                    )
                }
            }

            composable("manual_appraisal") {
                ManualAppraisalScreen(
                    linkedPin = scannerPin,
                    scrapperInput = scrapperInput,
                    conditionGrade = conditionGrade,
                    hasOriginalCard = hasOriginalCard,
                    customBaseValueUsd = customBaseValueUsd,
                    appraisalResult = appraisalResult,
                    onBack = { navController.popBackStack() },
                    onClearLinkedPin = viewModel::clearScannerPin,
                    onWaffleChange = viewModel::updateWafflePattern,
                    onEnamelChange = viewModel::updateEnamelQuality,
                    onBackstampChange = viewModel::updateBackstampStatus,
                    onWeightChange = viewModel::updateWeightGrams,
                    onThicknessChange = viewModel::updateThicknessMm,
                    onMagneticChange = viewModel::updateIsMagnetic,
                    onOcrTextChange = viewModel::updateOcrText,
                    onConditionGradeChange = viewModel::updateConditionGrade,
                    onHasOriginalCardChange = viewModel::updateHasOriginalCard,
                    onCustomBaseValueChange = viewModel::updateCustomBaseValue,
                    onSaveToPortfolio = { pin, fmv, grade, card ->
                        viewModel.addPinToPortfolio(
                            pin = pin,
                            purchasePriceUsd = fmv,
                            conditionGrade = grade,
                            hasOriginalCard = card
                        )
                    }
                )
            }

            composable(BottomTab.MY_PINS.route) {
                MyPinsScreen(
                    portfolioItems = portfolioItems,
                    traders = traders,
                    isoList = isoList,
                    tradeMatches = tradeMatches,
                    onPinClick = { pin ->
                        viewModel.selectPin(pin)
                        navController.navigate("detail")
                    },
                    onMarkTrader = { pin, grade ->
                        viewModel.addPinToTradeList(pin, TradeListType.TRADER, grade)
                    },
                    onRemovePortfolioItem = viewModel::removePortfolioItem,
                    onRemoveTradeEntry = viewModel::removeTradeEntry,
                    onBrowseCatalog = {
                        navController.navigate(BottomTab.CATALOG.route) {
                            popUpTo(BottomTab.SCAN.route) { saveState = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
