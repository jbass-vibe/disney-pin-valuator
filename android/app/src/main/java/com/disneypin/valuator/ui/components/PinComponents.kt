package com.disneypin.valuator.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.ui.theme.CastleBlue
import com.disneypin.valuator.ui.theme.GrailPurple
import com.disneypin.valuator.ui.theme.MagicGold
import com.disneypin.valuator.ui.theme.RiskHighRed
import com.disneypin.valuator.ui.theme.RiskLowGreen
import com.disneypin.valuator.ui.theme.RiskModAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun formatUsd(amount: Double): String = String.format(Locale.US, "$%.2f", amount)

/**
 * SRS 2.1 Trade-Priority Overlay Colors:
 * - Grail ($50+): Magenta/Red #FF0F4B + Gold border
 * - High ($30-$49): Bright Gold #FFDE00 + Dark text
 * - Good ($20-$29): Emerald Green #00B955 + White text
 * - Standard (<$20): Muted Slate #262A32 + Light text
 * - Low Confidence: Amber/Orange #EA580C + White text
 */
data class TierOverlayStyle(
    val bg: Color,
    val fg: Color,
    val border: Color
)

fun tierOverlayStyle(priceTier: String, isLowConf: Boolean = false): TierOverlayStyle {
    if (isLowConf) {
        return TierOverlayStyle(Color(0xFFEA580C), Color.White, Color.White)
    }
    return when {
        priceTier.startsWith("Grail") -> TierOverlayStyle(Color(0xFFFF0F4B), Color.White, Color(0xFFFFDE00))
        priceTier.startsWith("High") -> TierOverlayStyle(Color(0xFFFFDE00), Color(0xFF1A1C22), Color.White)
        priceTier.startsWith("Good") -> TierOverlayStyle(Color(0xFF00B955), Color.White, Color.White)
        else -> TierOverlayStyle(Color(0xFF262A32), Color(0xFFE4E7F0), Color(0xFF6B7280))
    }
}

@Composable
fun LocalFileThumbnail(
    filePath: String,
    contentDescription: String,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val bmpState = produceState<ImageBitmap?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            if (filePath.isBlank()) return@withContext null
            val f = File(filePath)
            if (!f.exists()) return@withContext null
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
        }
    }
    val img = bmpState.value
    if (img != null) {
        Image(
            bitmap = img,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
        )
    }
}

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
        tier.startsWith("Grail") -> Color(0xFFFF0F4B).copy(alpha = 0.14f) to Color(0xFFB80032)
        tier.startsWith("High") -> MagicGold.copy(alpha = 0.22f) to Color(0xFF7A5600)
        tier.startsWith("Good") -> Color(0xFF00B955).copy(alpha = 0.14f) to Color(0xFF007A38)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (pin.referenceImagePath.isNotBlank()) {
                    LocalFileThumbnail(
                        filePath = pin.referenceImagePath,
                        contentDescription = pin.pinName,
                        size = 52.dp
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
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
                if (pin.dataSource != "Seeded") {
                    BadgeChip(
                        text = "Gemini Learned",
                        backgroundColor = CastleBlue.copy(alpha = 0.14f),
                        contentColor = CastleBlue
                    )
                } else {
                    ScrapperRiskBadge(pin.scrapperRisk)
                }
            }
        }
    }
}
