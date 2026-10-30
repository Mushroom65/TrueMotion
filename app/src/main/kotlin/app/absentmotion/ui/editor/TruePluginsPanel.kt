package app.absentmotion.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.R
import app.absentmotion.compat.EffectMigration
import app.absentmotion.editor.EditorState
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.truemotion.Plugins
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun TruePluginsPanel(state: EditorState) {
    val context = LocalContext.current
    val language = currentLanguage(context).tag
    val plugins = remember { runCatching { Plugins.bundled(context) }.getOrDefault(emptyList()) }
    val prefs = remember { context.getSharedPreferences("true-plugins", 0) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    Text(stringResource(R.string.true_plugins_help), color = Am.OnDarkDim)
    for (plugin in plugins) {
        val enabled = remember(plugin.id, revision) { prefs.getBoolean(plugin.id, false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(plugin.label(plugin.manifest.opt("name"), language), Modifier.weight(1f), color = Am.OnDark)
            Switch(enabled, { value ->
                prefs.edit().putBoolean(plugin.id, value).apply()
                revision++
                scope.launch { state.engine.reloadEffects(context) }
            }, enabled = !busy)
        }
        if (enabled) for (command in plugin.commands) {
            var expanded by remember(plugin.id, command) { mutableStateOf(false) }
            val parameters = command.optJSONArray("params")
            val arguments = remember(plugin.id, command) { plugin.defaults(parameters) }
            val canRun = !busy && (!command.optBoolean("requiresSelection") || state.selectedId != null)
            OutlinedButton({
                if (!expanded && parameters != null && parameters.length() > 0) expanded = true
                else {
                    val before = state.scene
                    val selected = state.selectedId
                    val timeMs = state.timeMs
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val after = withContext(Dispatchers.Default) {
                                val catalog = state.engine.effects.value
                                val plan = Plugins.run(plugin, command.getString("id"), before, selected, timeMs, arguments)
                                Plugins.plan(before, plan.getJSONArray("operations"), timeMs, catalog, EffectMigration.read(context, catalog))
                            }
                            check(state.scene == before)
                            state.edit { after }
                            state.trueCommandRevision++
                        } catch (e: Exception) { error = e.message ?: e.toString() }
                        finally { busy = false }
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = canRun) { Text(plugin.label(command.opt("title"), language), color = Am.Accent) }
            if (expanded && parameters != null) for (i in 0 until parameters.length()) {
                val parameter = parameters.getJSONObject(i)
                val key = parameter.getString("key")
                var text by remember(plugin.id, command, key) { mutableStateOf(arguments.opt(key)?.toString().orEmpty()) }
                OutlinedTextField(text, {
                    text = it
                    val value: Any = if (parameter.opt("default") is Number) it.toDoubleOrNull() ?: it else it
                    arguments.put(key, value)
                }, Modifier.fillMaxWidth().padding(start = 8.dp), label = { Text(plugin.label(parameter.opt("label"), language)) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Am.OnDark, unfocusedTextColor = Am.OnDark,
                        focusedLabelColor = Am.Accent, unfocusedLabelColor = Am.OnDarkDim))
            }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Am.Accent)
    if (error != null) Text(stringResource(R.string.true_operation_error, error!!), color = Am.Warning)
}
