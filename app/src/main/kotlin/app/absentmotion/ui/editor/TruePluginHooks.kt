package app.absentmotion.ui.editor

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.absentmotion.compat.EffectMigration
import app.absentmotion.editor.EditorState
import app.absentmotion.truemotion.Plugins
import app.truemotion.port.PluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun TruePluginHooks(state: EditorState) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("true-plugins", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(state, revision) {
        val plugins = Plugins.bundled(context).filter { prefs.getBoolean(it.id, false) && (it.manifest.optJSONArray("hooks")?.length() ?: 0) > 0 }
        if (plugins.isEmpty()) return@LaunchedEffect
        val states = HashMap<String, JSONObject>()
        val failed = HashSet<String>()
        var ids = state.scene.layers.map { it.id }.toSet()
        var selection = state.selectedId
        var frame = state.frame
        var command = state.trueCommandRevision
        suspend fun dispatch(event: String, data: JSONObject = JSONObject()) {
            for (plugin in plugins) {
                if (plugin.id in failed) continue
                val hooks = plugin.manifest.getJSONArray("hooks")
                if ((0 until hooks.length()).none { hooks.getString(it) == event }) continue
                val before = state.scene
                val selected = state.selectedId
                val ms = state.timeMs
                try {
                    val (after, memory) = withContext(Dispatchers.Default) {
                        val plan = PluginRuntime.hook(plugin.script, event, Plugins.snapshot(before, selected, ms),
                            plugin.defaults(plugin.manifest.optJSONArray("settings")), data, states[plugin.id] ?: JSONObject())
                        val catalog = state.engine.effects.value
                        Plugins.plan(before, plan.getJSONArray("operations"), ms, catalog, EffectMigration.read(context, catalog)) to plan.getJSONObject("state")
                    }
                    if (state.scene == before) {
                        states[plugin.id] = memory
                        if (after != before) state.edit { after }
                    }
                } catch (e: Exception) {
                    failed += plugin.id
                    Log.w("TruePlugins", "Hook stopped for ${plugin.id}: $event", e)
                }
            }
            ids = state.scene.layers.map { it.id }.toSet()
        }
        dispatch("projectOpened")
        snapshotFlow { Triple(state.selectedId to state.frame, state.scene.layers.map { it.id }.toSet(), state.trueCommandRevision) }.collectLatest { (pose, nextIds, nextCommand) ->
            if (state.playing) { frame = pose.second; return@collectLatest }
            for (id in nextIds - ids) dispatch("layerAdded", JSONObject().put("id", "$id"))
            ids = state.scene.layers.map { it.id }.toSet()
            if (pose.first != selection) {
                selection = pose.first
                dispatch("selectionChanged", JSONObject().put("id", selection?.toString() ?: JSONObject.NULL))
            }
            if (nextCommand != command) { command = nextCommand; dispatch("commandFinished") }
            if (pose.second != frame) {
                frame = pose.second
                delay(100)
                dispatch("timeChanged", JSONObject().put("time", state.timeMs / 1000.0))
            }
        }
    }
}
