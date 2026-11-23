package com.disneypin.valuator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
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
import com.disneypin.valuator.ui.screens.PinDetailScreen
import com.disneypin.valuator.ui.screens.PortfolioScreen
import com.disneypin.valuator.ui.screens.ScannerScreen
import com.disneypin.valuator.ui.screens.TradesScreen
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
    CATALOG("catalog", "Catalog", Icons.Default.Search),
    EVALUATE("evaluate", "Evaluate", Icons.Default.FactCheck),
    PORTFOLIO("portfolio", "Portfolio", Icons.Default.CollectionsBookmark),
    TRADES("trades", "Trades", Icons.Default.SwapHoriz)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisneyPinValuatorApp(viewModel: PinValuatorViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val catalogStats by viewModel.catalogStats.collectAsState()
    val catalogPins by viewModel.catalogPins.collectAsState()
    val isLoadingCatalog by viewModel.isLoadingCatalog.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedPriceTier by viewModel.selectedPriceTier.collectAsState()
    val selectedRarity by viewModel.selectedRarity.collectAsState()

    val selectedPin by viewModel.selectedPin.collectAsState()
    val selectedPinHistory by viewModel.selectedPinHistory.collectAsState()

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

    val showBottomBar = currentRoute != "detail"

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
                                    popUpTo(BottomTab.CATALOG.route) { saveState = true }
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
            startDestination = BottomTab.CATALOG.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomTab.CATALOG.route) {
                CatalogScreen(
                    stats = catalogStats,
                    pins = catalogPins,
                    isLoading = isLoadingCatalog,
                    searchQuery = searchQuery,
                    selectedPriceTier = selectedPriceTier,
                    selectedRarity = selectedRarity,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onPriceTierChange = viewModel::updatePriceTierFilter,
                    onRarityChange = viewModel::updateRarityFilter,
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
                        onBack = { navController.popBackStack() },
                        onAddToPortfolio = { viewModel.addPinToPortfolio(it) },
                        onAddToTradeList = { p, type -> viewModel.addPinToTradeList(p, type) },
                        onEvaluatePin = { p ->
                            viewModel.startEvaluationForPin(p)
                            navController.navigate(BottomTab.EVALUATE.route) {
                                popUpTo(BottomTab.CATALOG.route) { saveState = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }

            composable(BottomTab.EVALUATE.route) {
                ScannerScreen(
                    linkedPin = scannerPin,
                    scrapperInput = scrapperInput,
                    conditionGrade = conditionGrade,
                    hasOriginalCard = hasOriginalCard,
                    customBaseValueUsd = customBaseValueUsd,
                    appraisalResult = appraisalResult,
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

            composable(BottomTab.PORTFOLIO.route) {
                PortfolioScreen(
                    items = portfolioItems,
                    onPinClick = { pin ->
                        viewModel.selectPin(pin)
                        navController.navigate("detail")
                    },
                    onMarkTrader = { pin, grade ->
                        viewModel.addPinToTradeList(pin, TradeListType.TRADER, grade)
                    },
                    onRemoveItem = viewModel::removePortfolioItem,
                    onBrowseCatalog = {
                        navController.navigate(BottomTab.CATALOG.route) {
                            popUpTo(BottomTab.CATALOG.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(BottomTab.TRADES.route) {
                TradesScreen(
                    traders = traders,
                    isoList = isoList,
                    matches = tradeMatches,
                    onPinClick = { pin ->
                        viewModel.selectPin(pin)
                        navController.navigate("detail")
                    },
                    onRemoveEntry = viewModel::removeTradeEntry
                )
            }
        }
    }
}
