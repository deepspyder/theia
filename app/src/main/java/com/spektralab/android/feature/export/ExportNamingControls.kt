package com.spektralab.android.feature.export

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.ExportNaming
import com.spektralab.android.feature.editor.EditorViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportNamingControls(vm: EditorViewModel, enabled: Boolean) {
    val naming = vm.exportNaming
    val legacy = stringResource(R.string.export_naming_timestamp)
    val custom = stringResource(R.string.export_naming_tokens)
    ExportSettingRow(stringResource(R.string.export_naming), if (naming.tokens.isEmpty()) legacy else custom,
        listOf(legacy, custom), enabled) {
        vm.exportNaming = if (it == legacy) ExportNaming() else ExportNaming(listOf(ExportNaming.Token.ORIGINAL))
    }
    if (naming.tokens.isNotEmpty()) {
        val labels = listOf(stringResource(R.string.export_name_original), stringResource(R.string.export_name_film),
            stringResource(R.string.export_name_paper), stringResource(R.string.export_name_date))
        Text(stringResource(R.string.export_name_help), Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportNaming.Token.entries.forEach { token ->
                FilterChip(selected = token in naming.tokens, enabled = enabled, onClick = {
                    val updated = if (token in naming.tokens) naming.tokens - token else naming.tokens + token
                    if (updated.isNotEmpty()) vm.exportNaming = naming.copy(tokens = updated)
                }, label = { Text(labels[token.ordinal]) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        naming.tokens.forEachIndexed { index, token ->
            val label = labels[token.ordinal]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. $label", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                fun move(to: Int) { val updated = naming.tokens.toMutableList(); updated.add(to, updated.removeAt(index)); vm.exportNaming = naming.copy(tokens = updated) }
                IconButton(onClick = { move(index - 1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.export_name_earlier, label))
                }
                IconButton(onClick = { move(index + 1) }, enabled = enabled && index < naming.tokens.lastIndex) {
                    Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.export_name_later, label))
                }
            }
        }
        val separators = listOf("_", "-", " ")
        val separatorLabels = listOf(stringResource(R.string.export_name_underscore), stringResource(R.string.export_name_hyphen), stringResource(R.string.export_name_space))
        ExportSettingRow(stringResource(R.string.export_name_separator), separatorLabels[separators.indexOf(naming.separator)],
            separatorLabels, enabled) { vm.exportNaming = naming.copy(separator = separators[separatorLabels.indexOf(it)]) }
    }
    Text(stringResource(R.string.export_name_example, vm.exportNameExample()), Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
