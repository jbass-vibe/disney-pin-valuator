package com.disneypin.valuator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.AppSettings
import com.disneypin.valuator.data.model.CatalogStats
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    settings: AppSettings,
    stats: CatalogStats?,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, model: String, staleDays: Int, autoVerifyOnline: Boolean) -> Unit,
    onTestConnection: suspend (apiKey: String, model: String) -> Result<String>
) {
    var apiKey by remember(settings) { mutableStateOf(settings.geminiApiKey) }
    var model by remember(settings) { mutableStateOf(settings.geminiModel) }
    var staleDays by remember(settings) { mutableIntStateOf(settings.stalePriceDays) }
    var autoVerifyOnline by remember(settings) { mutableStateOf(settings.autoVerifyOnline) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini & Offline DB Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Gemini Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-verify top high-value pins online (max 1 search call/board)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoVerifyOnline,
                        onCheckedChange = { autoVerifyOnline = it }
                    )
                }
                Text(
                    text = "Price Freshness Window: $staleDays days",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = staleDays.toFloat(),
                    onValueChange = { staleDays = it.roundToInt() },
                    valueRange = 14f..180f
                )

                stats?.let { s ->
                    Text(
                        text = "DB Stats: ${s.seededCount} Seeded • ${s.learnedCount} Gemini Learned • ${s.embeddingsCount} Visual Fingerprints",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        enabled = !isTesting && apiKey.isNotBlank(),
                        onClick = {
                            isTesting = true
                            testResult = "Testing..."
                            scope.launch {
                                val res = onTestConnection(apiKey, model)
                                testResult = res.fold(
                                    onSuccess = { "✓ $it" },
                                    onFailure = { "✗ ${it.message ?: "Failed"}" }
                                )
                                isTesting = false
                            }
                        }
                    ) {
                        Text("Test Connection")
                    }
                }
                testResult?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(apiKey, model, staleDays, autoVerifyOnline)
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
