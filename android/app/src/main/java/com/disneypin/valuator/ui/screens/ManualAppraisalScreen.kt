package com.disneypin.valuator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.AppraisalResult
import com.disneypin.valuator.data.model.BackstampStatus
import com.disneypin.valuator.data.model.EnamelQualityStatus
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.ScrapperAssessmentInput
import com.disneypin.valuator.data.model.ScrapperRiskLevel
import com.disneypin.valuator.data.model.WafflePatternStatus
import com.disneypin.valuator.domain.EvaluationEngine
import com.disneypin.valuator.ui.components.formatUsd
import com.disneypin.valuator.ui.theme.RiskHighRed
import com.disneypin.valuator.ui.theme.RiskLowGreen
import com.disneypin.valuator.ui.theme.RiskModAmber
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAppraisalScreen(
    linkedPin: Pin?,
    scrapperInput: ScrapperAssessmentInput,
    conditionGrade: Int,
    hasOriginalCard: Boolean,
    customBaseValueUsd: Double,
    appraisalResult: AppraisalResult?,
    onBack: () -> Unit,
    onClearLinkedPin: () -> Unit,
    onWaffleChange: (WafflePatternStatus) -> Unit,
    onEnamelChange: (EnamelQualityStatus) -> Unit,
    onBackstampChange: (BackstampStatus) -> Unit,
    onWeightChange: (String) -> Unit,
    onThicknessChange: (String) -> Unit,
    onMagneticChange: (Boolean) -> Unit,
    onOcrTextChange: (String) -> Unit,
    onConditionGradeChange: (Int) -> Unit,
    onHasOriginalCardChange: (Boolean) -> Unit,
    onCustomBaseValueChange: (String) -> Unit,
    onSaveToPortfolio: (Pin, Double, Int, Boolean) -> Unit
) {
    var weightText by remember { mutableStateOf("") }
    var thicknessText by remember { mutableStateOf("") }
    var baseValueText by remember(linkedPin) {
        mutableStateOf(linkedPin?.estMarketValueUsd?.toString() ?: customBaseValueUsd.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scrapper & Grade Check", fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (linkedPin != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Evaluating Catalog Pin (${linkedPin.pinId})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = linkedPin.pinName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = onClearLinkedPin) {
                            Icon(Icons.Default.Clear, contentDescription = "Unlink Pin")
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = baseValueText,
                    onValueChange = {
                        baseValueText = it
                        onCustomBaseValueChange(it)
                    },
                    label = { Text("Baseline Pin Value (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "1. Backstamp OCR / Tag Text",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = scrapperInput.ocrBackstampText,
                        onValueChange = onOcrTextChange,
                        label = { Text("e.g. © DISNEY FAC-036223-21045 LIMITED EDITION OF 500") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onOcrTextChange("© DISNEY FAC-036223-21045 LIMITED EDITION OF 500") },
                            label = { Text("Sample LE/FAC") }
                        )
                        AssistChip(
                            onClick = { onOcrTextChange("© DISNEY AP") },
                            label = { Text("Sample AP") }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "2. Authenticity & Scrapper Inspection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Back Waffle Pattern", style = MaterialTheme.typography.labelLarge)
                    WafflePatternStatus.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = scrapperInput.wafflePattern == option,
                                onClick = { onWaffleChange(option) }
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Text("Enamel & Color Consistency", style = MaterialTheme.typography.labelLarge)
                    EnamelQualityStatus.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = scrapperInput.enamelQuality == option,
                                onClick = { onEnamelChange(option) }
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Text("Backstamp Clarity", style = MaterialTheme.typography.labelLarge)
                    BackstampStatus.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = scrapperInput.backstampStatus == option,
                                onClick = { onBackstampChange(option) }
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = {
                                weightText = it
                                onWeightChange(it)
                            },
                            label = { Text("Weight (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = thicknessText,
                            onValueChange = {
                                thicknessText = it
                                onThicknessChange(it)
                            },
                            label = { Text("Thickness (mm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Magnetic Alloy Detected", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = scrapperInput.isMagnetic,
                            onCheckedChange = onMagneticChange
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "3. Condition Grading (10-Point Scale)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = EvaluationEngine.gradeLabel(conditionGrade),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = conditionGrade.toFloat(),
                        onValueChange = { onConditionGradeChange(it.roundToInt()) },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = hasOriginalCard,
                            onCheckedChange = onHasOriginalCardChange
                        )
                        Text("Original Card Backer / OEM Packaging Intact")
                    }
                }
            }

            appraisalResult?.let { res ->
                val (bannerColor, icon) = when (res.scrapperAssessment.riskLevel) {
                    ScrapperRiskLevel.LOW -> RiskLowGreen to Icons.Default.CheckCircle
                    ScrapperRiskLevel.MODERATE -> RiskModAmber to Icons.Default.Warning
                    ScrapperRiskLevel.HIGH -> RiskHighRed to Icons.Default.Warning
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(bannerColor.copy(alpha = 0.14f))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(icon, contentDescription = null, tint = bannerColor)
                                Column {
                                    Text(
                                        text = "${res.scrapperAssessment.riskLevel.label} (${res.scrapperAssessment.riskScore}/100)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = bannerColor
                                    )
                                    Text(
                                        text = "Adjusted FMV: ${formatUsd(res.adjustedFmvUsd)} • Liquidity: ${res.liquidityScore}/100",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        if (res.scrapperAssessment.detectedOcrTags.isNotEmpty()) {
                            Text(
                                text = "OCR Tags: ${res.scrapperAssessment.detectedOcrTags.joinToString(" • ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        res.scrapperAssessment.reasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (linkedPin != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    onSaveToPortfolio(
                                        linkedPin,
                                        res.adjustedFmvUsd,
                                        res.conditionGrade,
                                        res.hasOriginalCard
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Save Appraised Pin to Portfolio")
                            }
                        }
                    }
                }
            }
        }
    }
}
