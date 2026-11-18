package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.color.NeutralWhiteBalance
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun NeutralPickButton(vm: EditorViewModel, target: NeutralWhiteBalance.Target) {
    OutlinedButton(onClick = { vm.beginNeutralPick(target) }, enabled = vm.canPickNeutral,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = TheiaShapes.control) {
        Icon(Icons.Rounded.Colorize, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (target == NeutralWhiteBalance.Target.DECODE) R.string.neutral_pick_decode else R.string.neutral_pick_print))
    }
}

@Composable
internal fun RawWhiteBalanceOptions(vm: EditorViewModel) {
    val enabled = vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting
    var presets by remember { mutableStateOf(false) }
    val entries = listOf(R.string.neutral_preset_daylight to (5500f to 0f), R.string.neutral_preset_cloudy to (6500f to 0f),
        R.string.neutral_preset_shade to (7500f to 0f), R.string.neutral_preset_tungsten to (2850f to 0f),
        R.string.neutral_preset_fluorescent to (4000f to 20f), R.string.neutral_preset_flash to (6000f to 0f))
    Box {
        TextButton(onClick = { presets = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.neutral_raw_presets))
        }
        DropdownMenu(presets, onDismissRequest = { presets = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.neutral_preset_shot)) }, onClick = { presets = false; vm.resetCameraWb() })
            entries.forEach { (name, value) -> DropdownMenuItem(text = { Text(stringResource(name)) },
                onClick = { presets = false; vm.applyRawWhiteBalancePreset(value.first, value.second) }) }
        }
    }
    NeutralPickButton(vm, NeutralWhiteBalance.Target.DECODE)
    if (vm.params.cameraWbCustom) {
        val shot = vm.cameraAsShot
        Text(if (shot == null) stringResource(R.string.neutral_shot_unavailable)
            else stringResource(R.string.neutral_shot_value, shot.temperature, shot.tint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AxisSwitch(stringResource(R.string.neutral_raw_temperature_shot), vm.params.cameraWbTemperatureAsShot, enabled && shot != null) {
            vm.params.cameraWbTemperatureAsShot = it; vm.scheduleCameraWb()
        }
        AxisSwitch(stringResource(R.string.neutral_raw_tint_shot), vm.params.cameraWbTintAsShot, enabled && shot != null) {
            vm.params.cameraWbTintAsShot = it; vm.scheduleCameraWb()
        }
    }
}

@Composable
private fun AxisSwitch(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}
