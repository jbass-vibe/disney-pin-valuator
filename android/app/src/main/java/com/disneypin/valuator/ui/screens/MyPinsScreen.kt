package com.disneypin.valuator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PortfolioItem
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeMatch

@Composable
fun MyPinsScreen(
    portfolioItems: List<PortfolioItem>,
    traders: List<TradeListEntry>,
    isoList: List<TradeListEntry>,
    tradeMatches: List<TradeMatch>,
    onPinClick: (Pin) -> Unit,
    onMarkTrader: (Pin, Int) -> Unit,
    onRemovePortfolioItem: (Long) -> Unit,
    onRemoveTradeEntry: (Long) -> Unit,
    onBrowseCatalog: () -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }
    val sections = listOf("Portfolio (${portfolioItems.size})", "Trades & ISO")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            sections.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedSection == index,
                    onClick = { selectedSection = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size)
                ) {
                    Text(label)
                }
            }
        }

        if (selectedSection == 0) {
            PortfolioScreen(
                items = portfolioItems,
                onPinClick = onPinClick,
                onMarkTrader = onMarkTrader,
                onRemoveItem = onRemovePortfolioItem,
                onBrowseCatalog = onBrowseCatalog
            )
        } else {
            TradesScreen(
                traders = traders,
                isoList = isoList,
                matches = tradeMatches,
                onPinClick = onPinClick,
                onRemoveEntry = onRemoveTradeEntry
            )
        }
    }
}
