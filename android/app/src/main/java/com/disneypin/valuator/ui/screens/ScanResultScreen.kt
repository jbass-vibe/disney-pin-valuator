package com.disneypin.valuator.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.disneypin.valuator.data.model.MatchSource
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.ScanBatchState
import com.disneypin.valuator.data.model.ScannedPinResult
import com.disneypin.valuator.ui.components.BadgeChip
import com.disneypin.valuator.ui.components.LocalFileThumbnail
import com.disneypin.valuator.ui.components.PriceTierBadge
import com.disneypin.valuator.ui.components.ScrapperRiskBadge
import com.disneypin.valuator.ui.components.tierOverlayStyle
import com.disneypin.valuator.ui.theme.CastleBlue
import com.disneypin.valuator.ui.theme.RiskLowGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    batchState: ScanBatchState,
    onBack: () -> Unit,
    onSelectDetection: (String?) -> Unit,
    onOpenPinDetail: (Pin) -> Unit,
    onRefreshPin: (Pin) -> Unit,
    onSaveToPortfolio: (Pin) -> Unit,
    onEditPin: (Pin, String, String, Double, Double, String, String) -> Unit
) {
    val allPins = batchState.allPinsRanked
    val grailCount = allPins.count { it.priceTier.startsWith("Grail") }
    val highCount = allPins.count { it.priceTier.startsWith("High") }

    var editingPin by remember { mutableStateOf<Pin?>(null) }

    editingPin?.let { pinToEdit ->
        EditPinDialog(
            pin = pinToEdit,
            onDismiss = { editingPin = null },
            onSave = { name, col, low, high, rarity, risk ->
                onEditPin(pinToEdit, name, col, low, high, rarity, risk)
                editingPin = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${allPins.size} Pins • $grailCount Grails • $highCount High",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (batchState.statusMessage.isNotBlank()) {
                            Text(
                                text = batchState.statusMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (batchState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!batchState.errorMessage.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFEBEE))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = batchState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB71C1C)
                    )
                }
            }

            if (batchState.photos.isNotEmpty()) {
                val pagerState = rememberPagerState(pageCount = { batchState.photos.size })
                LaunchedEffect(batchState.selectedPhotoIndex) {
                    if (batchState.selectedPhotoIndex in 0 until batchState.photos.size) {
                        pagerState.animateScrollToPage(batchState.selectedPhotoIndex)
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .background(Color(0xFF12151C))
                ) { page ->
                    val photo = batchState.photos[page]
                    AnnotatedPinPhoto(
                        imagePath = photo.imagePath,
                        pins = photo.pins,
                        selectedDetectionId = batchState.selectedDetectionId,
                        onPillClick = { det -> onSelectDetection(det.detectionId) }
                    )
                }

                if (batchState.photos.size > 1) {
                    Text(
                        text = "Photo ${pagerState.currentPage + 1} of ${batchState.photos.size} (swipe to switch)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 4.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(allPins, key = { it.detectionId }) { det ->
                    val isSelected = det.detectionId == batchState.selectedDetectionId
                    val borderMod = if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                    } else Modifier

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(borderMod)
                            .clickable {
                                onSelectDetection(det.detectionId)
                                det.matchedPin?.let(onOpenPinDetail)
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                LocalFileThumbnail(
                                    filePath = det.cropImagePath,
                                    contentDescription = det.displayTitle,
                                    size = 56.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = det.displayTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (det.displayCollection.isNotBlank()) {
                                        Text(
                                            text = det.displayCollection,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PriceTierBadge(det.priceTier)
                                        val srcColor = when (det.matchSource) {
                                            MatchSource.GEMINI_SEARCH -> CastleBlue
                                            MatchSource.GEMINI_ESTIMATE -> Color(0xFF9C6500)
                                            else -> RiskLowGreen
                                        }
                                        BadgeChip(
                                            text = det.matchSource.label,
                                            backgroundColor = srcColor.copy(alpha = 0.14f),
                                            contentColor = srcColor
                                        )
                                        if (det.scrapperRisk != "Low") {
                                            ScrapperRiskBadge(det.scrapperRisk)
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    when {
                                        det.matchSource == MatchSource.PENDING_SEARCH && det.errorNote == null -> {
                                            Text(
                                                text = "...",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        det.errorNote != null && det.estMarketValueUsd <= 0.0 -> {
                                            Text(
                                                text = "Unverified",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB71C1C)
                                            )
                                        }
                                        else -> {
                                            Text(
                                                text = "$${det.marketLowUsd.toInt()}–$${det.marketHighUsd.toInt()}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            det.errorNote?.let { err ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB71C1C),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            det.matchedPin?.let { pin ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { onRefreshPin(pin) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                        Text("Refresh")
                                    }
                                    TextButton(onClick = { editingPin = pin }) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                        Text("Edit")
                                    }
                                    TextButton(onClick = { onSaveToPortfolio(pin) }) {
                                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                                        Text("Save")
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

@Composable
private fun AnnotatedPinPhoto(
    imagePath: String,
    pins: List<ScannedPinResult>,
    selectedDetectionId: String?,
    onPillClick: (ScannedPinResult) -> Unit
) {
    val bmpState = produceState<ImageBitmap?>(initialValue = null, key1 = imagePath) {
        value = withContext(Dispatchers.IO) {
            val f = File(imagePath)
            if (!f.exists()) return@withContext null
            BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
        }
    }

    val bmp = bmpState.value
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            val density = LocalDensity.current
            val containerW = with(density) { maxWidth.toPx() }
            val containerH = with(density) { maxHeight.toPx() }
            val scale = min(containerW / bmp.width.toFloat(), containerH / bmp.height.toFloat())
            val dispW = bmp.width * scale
            val dispH = bmp.height * scale
            val offsetX = (containerW - dispW) / 2f
            val offsetY = (containerH - dispH) / 2f

            Image(
                bitmap = bmp,
                contentDescription = "Scanned Pin Board",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            pins.forEach { det ->
                val isSearching = det.matchSource == MatchSource.PENDING_SEARCH && det.errorNote == null
                val hasError = det.errorNote != null && det.estMarketValueUsd <= 0.0
                val isLowConf = det.priceConfidence == "Low Conf" || hasError
                val style = tierOverlayStyle(det.priceTier, isLowConf = isLowConf)
                val pillLabel = when {
                    isSearching -> "..."
                    hasError -> "?"
                    det.priceTier.startsWith("Grail") -> "★$${det.estMarketValueUsd.toInt()}+"
                    det.priceTier.startsWith("High") -> "★$${det.marketLowUsd.toInt()}-$${det.marketHighUsd.toInt()}"
                    else -> "$${det.marketLowUsd.toInt()}-$${det.marketHighUsd.toInt()}"
                }

                val pxX = (offsetX + det.box2d.centerX * dispW).roundToInt()
                val pxY = (offsetY + det.box2d.bottomY * dispH).roundToInt()
                val isSelected = det.detectionId == selectedDetectionId

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset((pxX - 24).coerceIn(0, (containerW - 48).toInt()), (pxY - 16).coerceIn(0, (containerH - 20).toInt())) }
                        .clip(RoundedCornerShape(6.dp))
                        .background(style.bg)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Color.Cyan else style.border, RoundedCornerShape(6.dp))
                        .clickable { onPillClick(det) }
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = pillLabel,
                        color = style.fg,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
