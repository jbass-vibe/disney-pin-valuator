package com.disneypin.valuator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PriceHistoryEntry
import com.disneypin.valuator.data.model.TradeListType
import com.disneypin.valuator.ui.components.PriceTierBadge
import com.disneypin.valuator.ui.components.RarityBadge
import com.disneypin.valuator.ui.components.ScrapperRiskBadge
import com.disneypin.valuator.ui.components.formatUsd

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PinDetailScreen(
    pin: Pin,
    priceHistory: List<PriceHistoryEntry>,
    onBack: () -> Unit,
    onAddToPortfolio: (Pin) -> Unit,
    onAddToTradeList: (Pin, TradeListType) -> Unit,
    onEvaluatePin: (Pin) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pin.pinId, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = pin.pinName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PriceTierBadge(pin.priceTier)
                RarityBadge(pin.rarity)
                ScrapperRiskBadge(pin.scrapperRisk)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Estimated Fair Market Value",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = formatUsd(pin.estMarketValueUsd),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Market Range: ${formatUsd(pin.marketLowUsd)} – ${formatUsd(pin.marketHighUsd)} • Confidence: ${pin.priceConfidence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Updated: ${pin.priceUpdated}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Pin Metadata",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    MetadataRow("Collection", pin.collection)
                    if (pin.subSet.isNotBlank()) MetadataRow("Sub-Set", pin.subSet)
                    if (pin.resort.isNotBlank()) MetadataRow("Park / Resort", pin.resort)
                    if (pin.yearWave.isNotBlank()) MetadataRow("Year / Wave", pin.yearWave)
                    if (pin.pinpicsId.isNotBlank()) MetadataRow("PinPics ID", pin.pinpicsId)
                    if (pin.visualTags.isNotBlank()) MetadataRow("Visual Tags", pin.visualTags)
                    if (pin.notes.isNotBlank()) MetadataRow("Notes", pin.notes)
                }
            }

            if (priceHistory.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Price History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        priceHistory.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(entry.recordedAt, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${formatUsd(entry.lowUsd)} – ${formatUsd(entry.highUsd)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Button(
                onClick = { onEvaluatePin(pin) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FactCheck, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Run Scrapper & Grade Check")
            }

            Button(
                onClick = { onAddToPortfolio(pin) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddCircle, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Add to My Portfolio")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onAddToTradeList(pin, TradeListType.TRADER) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("Mark Trader")
                }
                OutlinedButton(
                    onClick = { onAddToTradeList(pin, TradeListType.ISO) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Add to ISO")
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.62f)
        )
    }
}
