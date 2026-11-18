package com.spektralab.android.feature.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.feature.editor.EditorViewModel

@Composable
internal fun ExportRecipeManager(vm: EditorViewModel, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<ExportRecipe?>(null) }
    var deleting by remember { mutableStateOf<ExportRecipe?>(null) }
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = { if (!vm.recipesBusy) onDismiss() },
        title = { Text(stringResource(R.string.export_recipes_manage)) }, text = {
            Column {
                vm.recipeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (vm.recipes.isEmpty()) Text(stringResource(R.string.export_recipes_empty))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(vm.recipes, key = { it.name }) { recipe ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(recipe.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text("${recipe.format} · ${sizeSummary(context, recipe.res, recipe.customSize)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { renaming = recipe; name = recipe.name }, enabled = !vm.recipesBusy) {
                                Icon(Icons.Rounded.Edit, stringResource(R.string.export_recipe_rename) + " " + recipe.name)
                            }
                            IconButton(onClick = { deleting = recipe }, enabled = !vm.recipesBusy) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.export_recipe_delete) + " " + recipe.name)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !vm.recipesBusy) { Text(stringResource(R.string.export_done)) } })
    renaming?.let { recipe ->
        AlertDialog(onDismissRequest = { if (!vm.recipesBusy) renaming = null },
            title = { Text(stringResource(R.string.export_recipe_rename)) }, text = {
                Column {
                    OutlinedTextField(name, { name = it.take(80) }, singleLine = true,
                        enabled = !vm.recipesBusy, label = { Text(stringResource(R.string.export_recipe_name)) })
                    vm.recipeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(onClick = { vm.renameRecipe(recipe, name) { renaming = null } },
                enabled = !vm.recipesBusy && name.isNotBlank()) { Text(stringResource(R.string.export_recipe_rename)) } },
            dismissButton = { TextButton(onClick = { renaming = null }, enabled = !vm.recipesBusy) { Text(stringResource(R.string.export_cancel)) } })
    }
    deleting?.let { recipe ->
        AlertDialog(onDismissRequest = { if (!vm.recipesBusy) deleting = null },
            title = { Text(stringResource(R.string.export_recipe_delete_title, recipe.name)) },
            text = { Column {
                Text(stringResource(R.string.export_recipe_delete_help))
                vm.recipeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { vm.deleteRecipe(recipe) { deleting = null } }, enabled = !vm.recipesBusy) {
                Text(stringResource(R.string.export_recipe_delete), color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deleting = null }, enabled = !vm.recipesBusy) { Text(stringResource(R.string.export_cancel)) } })
    }
}
