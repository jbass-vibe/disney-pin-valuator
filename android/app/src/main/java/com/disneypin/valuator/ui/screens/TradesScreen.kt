package com.disneypin.valuator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeMatch
import com.disneypin.valuator.ui.components.BadgeChip
import com.disneypin.valuator.ui.components.formatUsd
import com.disneypin.valuator.ui.theme.RiskHighRed
import com.disneypin.valuator.ui.theme.RiskLowGreen
import com.disneypin.valuator.ui.theme.RiskModAmber

@Composable
fun TradesScreen(
    traders: List<TradeListEntry>,
    isoList: List<TradeListEntry>,
    matches: List<TradeMatch>,
    onPinClick: (Pin) -> Unit,
    onRemoveEntry: (Long) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Matches (${matches.size})", "Traders (${traders.size})", "ISO (${isoList.size})")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "Trade & ISO Matcher",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                SegmentedButton(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size)
                ) {
                    Text(title, maxLines = 1)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            0 -> {
                if (matches.isEmpty()) {
                    EmptyTradeMessage(
                        "No fair-value matches yet.\nAdd pins to both your Traders and ISO lists from the Catalog to auto-match fair trades."
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(matches) { match ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val badgeColor = if (match.isFairMatch) RiskLowGreen else RiskModAmber
                                        BadgeChip(
                                            text = "${match.parityPercent}% Fair-Value Parity",
                                            backgroundColor = badgeColor.copy(alpha = 0.14f),
                                            contentColor = badgeColor
                                        )
                                        val diffSign = if (match.valueDiffUsd >= 0) "+" else ""
                                        Text(
                                            text = "Diff: $diffSign${formatUsd(match.valueDiffUsd)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onPinClick(match.yourTrader.pin) }
                                        ) {
                                            Text("YOUR TRADER", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                match.yourTrader.pin.pinName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                formatUsd(match.yourValueUsd),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Icon(
                                            Icons.Default.SwapHoriz,
                                            contentDescription = null,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onPinClick(match.targetIso) },
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            Text("TARGET ISO", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                match.targetIso.pinName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                formatUsd(match.targetValueUsd),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> TradeEntryList(
                entries = traders,
                emptyMessage = "No pins marked as Traders yet.",
                onPinClick = onPinClick,
                onRemoveEntry = onRemoveEntry
            )

            2 -> TradeEntryList(
                entries = isoList,
                emptyMessage = "Your ISO (In Search Of) list is empty.",
                onPinClick = onPinClick,
                onRemoveEntry = onRemoveEntry
            )
        }
    }
}

@Composable
private fun TradeEntryList(
    entries: List<TradeListEntry>,
    emptyMessage: String,
    onPinClick: (Pin) -> Unit,
    onRemoveEntry: (Long) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyTradeMessage(emptyMessage)
    } else {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPinClick(entry.pin) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.pin.pinName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${entry.pin.collection} • FMV ${formatUsd(entry.pin.estMarketValueUsd)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRemoveEntry(entry.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = RiskHighRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTradeMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
