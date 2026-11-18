package com.spektralab.android.feature.export

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.ExportSize
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.DarkroomChoiceStrip
import java.math.BigDecimal

internal fun sizeSummary(context: Context, resolution: Int, custom: ExportSize?): String {
    if (custom == null) return if (resolution == 0) context.getString(R.string.export_size_original)
        else context.getString(R.string.export_size_long_value, resolution.toString())
    val label = when (custom.kind) {
        ExportSize.Kind.LONG_EDGE -> R.string.export_size_long_value
        ExportSize.Kind.SHORT_EDGE -> R.string.export_size_short_value
        ExportSize.Kind.MEGAPIXELS -> R.string.export_size_mp_value
    }
    return context.getString(label, BigDecimal.valueOf(custom.value).stripTrailingZeros().toPlainString())
}

@Composable
internal fun ExportSizeControls(vm: EditorViewModel, enabled: Boolean) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    val original = stringResource(R.string.export_size_original)
    val custom = stringResource(R.string.export_size_custom)
    ExportSettingRow(stringResource(R.string.export_size), sizeSummary(context, vm.exportRes, vm.exportCustomSize),
        listOf("2048", "2560", "3840", original, custom), enabled,
        optionLabel = { it.toIntOrNull()?.let { edge -> sizeSummary(context, edge, null) } ?: it }) {
        if (it == custom) editing = true
        else { vm.exportCustomSize = null; vm.exportRes = it.toIntOrNull() ?: 0 }
    }
    if (editing) CustomSizeDialog(vm.exportCustomSize, onDismiss = { editing = false }) {
        vm.exportCustomSize = it
        editing = false
    }
}

@Composable
private fun CustomSizeDialog(current: ExportSize?, onDismiss: () -> Unit, onApply: (ExportSize) -> Unit) {
    var kindName by rememberSaveable { mutableStateOf((current?.kind ?: ExportSize.Kind.LONG_EDGE).name) }
    var value by rememberSaveable { mutableStateOf(current?.value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "2048") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val kind = ExportSize.Kind.valueOf(kindName)
    val parsed = value.replace(',', '.').toDoubleOrNull()?.let { runCatching { ExportSize(kind, it) }.getOrNull() }
    val labels = listOf(stringResource(R.string.export_size_long), stringResource(R.string.export_size_short), stringResource(R.string.export_size_mp))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.export_size_custom)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DarkroomChoiceStrip(labels, labels[kind.ordinal]) {
                val next = ExportSize.Kind.entries[labels.indexOf(it)]
                if (kind != next) { kindName = next.name; value = if (next == ExportSize.Kind.MEGAPIXELS) "12" else "2048"; touched = false }
            }
            OutlinedTextField(value, { value = it.take(16); touched = true }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(if (kind == ExportSize.Kind.MEGAPIXELS) R.string.export_size_mp_unit else R.string.export_size_pixels)) },
                keyboardOptions = KeyboardOptions(keyboardType = if (kind == ExportSize.Kind.MEGAPIXELS) KeyboardType.Decimal else KeyboardType.Number),
                isError = touched && parsed == null,
                supportingText = { if (touched && parsed == null) Text(stringResource(if (kind == ExportSize.Kind.MEGAPIXELS) R.string.export_size_mp_error else R.string.export_size_pixel_error)) })
            Text(stringResource(R.string.export_size_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text(stringResource(R.string.export_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_cancel)) } })
}
