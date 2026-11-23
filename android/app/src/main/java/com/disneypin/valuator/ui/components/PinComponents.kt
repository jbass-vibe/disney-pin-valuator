package com.disneypin.valuator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.ui.theme.CastleBlue
import com.disneypin.valuator.ui.theme.GrailPurple
import com.disneypin.valuator.ui.theme.MagicGold
import com.disneypin.valuator.ui.theme.RiskHighRed
import com.disneypin.valuator.ui.theme.RiskLowGreen
import com.disneypin.valuator.ui.theme.RiskModAmber
import java.util.Locale

fun formatUsd(amount: Double): String = String.format(Locale.US, "$%.2f", amount)

@Composable
fun BadgeChip(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PriceTierBadge(tier: String) {
    val (bg, fg) = when {
        tier.startsWith("Grail") -> GrailPurple.copy(alpha = 0.14f) to GrailPurple
        tier.startsWith("High") -> MagicGold.copy(alpha = 0.22f) to Color(0xFF7A5600)
        tier.startsWith("Good") -> CastleBlue.copy(alpha = 0.14f) to CastleBlue
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    BadgeChip(text = tier.ifBlank { "Standard" }, backgroundColor = bg, contentColor = fg)
}

@Composable
fun ScrapperRiskBadge(risk: String) {
    val (bg, fg) = when (risk.lowercase()) {
        "high", "high risk / likely scrapper" -> RiskHighRed.copy(alpha = 0.14f) to RiskHighRed
        "medium", "moderate", "moderate risk" -> RiskModAmber.copy(alpha = 0.16f) to RiskModAmber
        else -> RiskLowGreen.copy(alpha = 0.14f) to RiskLowGreen
    }
    BadgeChip(text = "Scrapper: $risk", backgroundColor = bg, contentColor = fg)
}

@Composable
fun RarityBadge(rarity: String) {
    val (bg, fg) = when {
        rarity.contains("Super", ignoreCase = true) -> GrailPurple.copy(alpha = 0.14f) to GrailPurple
        rarity.contains("Chaser", ignoreCase = true) -> CastleBlue.copy(alpha = 0.14f) to CastleBlue
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    BadgeChip(text = rarity, backgroundColor = bg, contentColor = fg)
}

@Composable
fun PinCard(
    pin: Pin,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = pin.pinName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val subtitle = listOfNotNull(
                        pin.collection.takeIf { it.isNotBlank() },
                        pin.yearWave.takeIf { it.isNotBlank() },
                        pin.resort.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatUsd(pin.estMarketValueUsd),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$${pin.marketLowUsd.toInt()}-$${pin.marketHighUsd.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PriceTierBadge(pin.priceTier)
                RarityBadge(pin.rarity)
                ScrapperRiskBadge(pin.scrapperRisk)
            }
        }
    }
}
