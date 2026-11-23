package com.disneypin.valuator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.disneypin.valuator.data.model.Pin

@Composable
fun EditPinDialog(
    pin: Pin,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        collection: String,
        lowUsd: Double,
        highUsd: Double,
        rarity: String,
        scrapperRisk: String
    ) -> Unit
) {
    var name by remember(pin) { mutableStateOf(pin.pinName) }
    var collection by remember(pin) { mutableStateOf(pin.collection) }
    var lowText by remember(pin) { mutableStateOf(pin.marketLowUsd.toString()) }
    var highText by remember(pin) { mutableStateOf(pin.marketHighUsd.toString()) }
    var rarity by remember(pin) { mutableStateOf(pin.rarity) }
    var risk by remember(pin) { mutableStateOf(pin.scrapperRisk) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Pin Details", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Pin Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = collection,
                    onValueChange = { collection = it },
                    label = { Text("Collection / Series") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lowText,
                        onValueChange = { lowText = it },
                        label = { Text("Low ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = highText,
                        onValueChange = { highText = it },
                        label = { Text("High ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("Rarity", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Common", "Chaser / Completer", "Super Chaser").forEach { opt ->
                        FilterChip(
                            selected = rarity == opt,
                            onClick = { rarity = opt },
                            label = { Text(opt.substringBefore(" /")) }
                        )
                    }
                }
                Text("Scrapper Risk", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Low", "Medium", "High").forEach { opt ->
                        FilterChip(
                            selected = risk == opt,
                            onClick = { risk = opt },
                            label = { Text(opt) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val low = lowText.toDoubleOrNull() ?: pin.marketLowUsd
                val high = highText.toDoubleOrNull() ?: pin.marketHighUsd
                onSave(name.ifBlank { pin.pinName }, collection, low, high, rarity, risk)
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
