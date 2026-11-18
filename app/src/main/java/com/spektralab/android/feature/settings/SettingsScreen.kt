package com.spektralab.android.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.data.diagnostics.DiagnosticReport
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.ui.theme.TheiaShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-wide preferences use the existing Material typography, controls and scrolling layout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(onBack: () -> Unit, automatic: Boolean, onAutomaticChange: (Boolean) -> Unit,
                           workspace: DiagnosticReport.Workspace, controller: SettingsViewModel) {
    val context = LocalContext.current
    var confirmation by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val latestWorkspace by rememberUpdatedState(workspace)
    LaunchedEffect(Unit) { controller.refresh() }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        controller.export(it,latestWorkspace)
    }
    fun back() { if (controller.exporting) controller.cancel(); onBack() }
    BackHandler(onBack = ::back)
    LaunchedEffect(controller.message) {
        controller.message?.let { snackbar.showSnackbar(it); controller.dismissMessage() }
    }
    val editable = !controller.busy && !controller.unavailable
    val value = controller.value
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title),style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = ::back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack,stringResource(R.string.settings_back))
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), expandedHeight = 56.dp) },
        snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets),contentAlignment = Alignment.TopCenter) {
            val contentWidth = if (maxWidth > maxHeight && maxHeight < 500.dp) 880.dp else 720.dp
            Column(Modifier.widthIn(max = contentWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp,vertical = 20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (controller.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.settings_working),style = MaterialTheme.typography.bodyMedium)
                }
                if (controller.unavailable) Text(stringResource(R.string.settings_unavailable),color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                SectionTitle(R.string.settings_preview_title)
                Choice(stringResource(R.string.settings_preview_edge),AppSettings.PREVIEW_EDGES.map { it.toString() to stringResource(R.string.settings_pixel_size,it) },
                    value.previewLongEdge.toString(),editable) { controller.update(value.copy(previewLongEdge = it.toInt())) }
                Help(R.string.settings_preview_help)

                SectionTitle(R.string.settings_material_title)
                Choice(stringResource(R.string.settings_film),controller.films.map { it.id to it.name },value.defaultFilm,editable) { controller.chooseFilm(it) }
                Choice(stringResource(R.string.settings_paper),controller.papers.filter { StockCatalog.canPrint(value.defaultFilm,it.id) }.map { it.id to it.name },
                    value.defaultPaper,editable) { controller.update(value.copy(defaultPaper = it)) }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_automatic),Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
                    Switch(automatic,onAutomaticChange,enabled = !controller.busy)
                }
                Help(R.string.settings_material_help)

                SectionTitle(R.string.settings_cache_title)
                Choice(stringResource(R.string.settings_cache_budget),AppSettings.CACHE_LIMITS.map {
                    it.toString() to if(it == 0) stringResource(R.string.settings_cache_off) else stringResource(R.string.settings_cache_size,it)
                },value.renderCacheMiB.toString(),editable) { controller.update(value.copy(renderCacheMiB = it.toInt())) }
                controller.storage?.let { storage ->
                    Text(stringResource(R.string.settings_cache_used,android.text.format.Formatter.formatShortFileSize(context,storage.bytes),storage.entries),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_cache_leased,android.text.format.Formatter.formatShortFileSize(context,storage.leasedBytes),storage.readers),
                        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Help(R.string.settings_cache_help)
                OutlinedButton(onClick = { confirmation = "cache" },enabled = !controller.busy,shape = TheiaShapes.control,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_cache_clear)) }
                TextButton(onClick = { controller.refresh() },enabled = !controller.busy,modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_refresh))
                }

                SectionTitle(R.string.settings_diagnostics_title)
                Help(R.string.settings_diagnostics_help)
                Button(onClick = {
                    document.launch("Theia-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date())}.zip")
                },enabled = !controller.busy,shape = TheiaShapes.control,modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_diagnostics_export))
                }
                if(controller.exporting) TextButton(onClick = controller::cancel,modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_diagnostics_cancel))
                }
                TextButton(onClick = { confirmation = "defaults" },enabled = editable,modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_restore))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    confirmation?.let { kind ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if(kind == "cache") R.string.settings_cache_confirm else R.string.settings_restore_confirm)) },
            text = { Help(if(kind == "cache") R.string.settings_cache_help else R.string.settings_restore_help) },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.settings_cancel)) } },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if(kind == "cache") controller.clearCache() else controller.update(AppSettings())
            },enabled = !controller.busy) { Text(stringResource(R.string.settings_confirm)) } })
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(stringResource(title),Modifier.padding(top = 12.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Help(text: Int) { Text(stringResource(text),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, choices: List<Pair<String,String>>, selected: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded,onExpandedChange = { if(enabled) expanded = it }) {
        OutlinedTextField(value = choices.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.settings_unknown_material),
            onValueChange = {},readOnly = true,enabled = enabled,label = { Text(label) },singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded,onDismissRequest = { expanded = false },modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { (id,name) -> DropdownMenuItem(text = { Text(name) },onClick = { expanded = false; onChange(id) }) }
        }
    }
}
