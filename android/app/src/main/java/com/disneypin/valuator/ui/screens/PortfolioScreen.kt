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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PortfolioItem
import com.disneypin.valuator.data.model.TradeListType
import com.disneypin.valuator.domain.EvaluationEngine
import com.disneypin.valuator.ui.components.BadgeChip
import com.disneypin.valuator.ui.components.formatUsd
import com.disneypin.valuator.ui.theme.CastleBlue
import com.disneypin.valuator.ui.theme.RiskHighRed
import com.disneypin.valuator.ui.theme.RiskLowGreen
import com.disneypin.valuator.ui.theme.RoyalNavy

@Composable
fun PortfolioScreen(
    items: List<PortfolioItem>,
    onPinClick: (Pin) -> Unit,
    onMarkTrader: (Pin, Int) -> Unit,
    onRemoveItem: (Long) -> Unit,
    onBrowseCatalog: () -> Unit
) {
    val totalFmv = items.sumOf {
        it.pin.estMarketValueUsd * EvaluationEngine.conditionMultiplier(it.conditionGrade, it.hasOriginalCard)
    }
    val totalCost = items.sumOf { it.purchasePriceUsd }
    val gainLoss = totalFmv - totalCost

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(RoyalNavy, CastleBlue)))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = "Portfolio Net Worth",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Text(
                    text = formatUsd(totalFmv),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${items.size} Pins • Cost: ${formatUsd(totalCost)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                    val sign = if (gainLoss >= 0) "+" else ""
                    Text(
                        text = "$sign${formatUsd(gainLoss)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (gainLoss >= 0) Color(0xFF9DF2BE) else Color(0xFFFFB4AB)
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Your pin portfolio is empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Browse the catalog or run an appraisal to track your collection's market value.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = onBrowseCatalog) {
                        Text("Explore Pin Catalog")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val adjustedFmv = item.pin.estMarketValueUsd *
                        EvaluationEngine.conditionMultiplier(item.conditionGrade, item.hasOriginalCard)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPinClick(item.pin) },
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
                                    text = item.pin.pinName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${item.pin.collection} • Cost: ${formatUsd(item.purchasePriceUsd)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BadgeChip(
                                        text = "Grade ${item.conditionGrade}/10",
                                        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    if (item.hasOriginalCard) {
                                        BadgeChip(
                                            text = "On Card",
                                            backgroundColor = RiskLowGreen.copy(alpha = 0.14f),
                                            contentColor = RiskLowGreen
                                        )
                                    }
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatUsd(adjustedFmv),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row {
                                    IconButton(onClick = { onMarkTrader(item.pin, item.conditionGrade) }) {
                                        Icon(
                                            Icons.Default.SwapHoriz,
                                            contentDescription = "Mark as Trader",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    IconButton(onClick = { onRemoveItem(item.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove",
                                            tint = RiskHighRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
