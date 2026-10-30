package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.truemotion.Studio
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object TransformClipboard { var layer: Layer? = null }

@Composable
internal fun TrueStudioPanel(state: EditorState, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var operation by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    fun apply(change: (Scene) -> Scene) {
        if (busy) return
        val before = state.scene
        busy = true
        error = null
        scope.launch {
            try {
                val after = withContext(Dispatchers.Default) { change(before) }
                check(state.scene == before)
                state.edit { after }
                onClose()
            } catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 700.dp),
            shape = RoundedCornerShape(20.dp), color = Am.Panel, contentColor = Am.OnDark) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.true_studio), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClose, enabled = !busy) { Text(stringResource(R.string.true_close), color = Am.Accent) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(R.string.true_animation, R.string.true_layers, R.string.true_color, R.string.true_plugins).forEachIndexed { i, title ->
                        TextButton({ tab = i; operation = 0; error = null }, enabled = !busy) {
                            Text(stringResource(title), color = if (tab == i) Am.Accent else Am.OnDarkDim)
                        }
                    }
                }
                Text(state.selected?.label ?: stringResource(R.string.true_select_layer), color = Am.OnDarkDim)
                val selected = state.selectedId
                if (tab != 3 && tab != 1 && selected == null) Text(stringResource(R.string.true_select_layer))
                else when (tab) {
                    0 -> {
                        if (operation == 1) {
                            var expr by remember { mutableStateOf("wiggle(2,20)") }
                            var property by remember { mutableIntStateOf(0) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf(R.string.true_position, R.string.true_scale, R.string.true_rotation, R.string.true_opacity).forEachIndexed { i, name ->
                                    TextButton({ property = i }) { Text(stringResource(name), color = if (property == i) Am.Accent else Am.OnDarkDim) }
                                }
                            }
                            OutlinedTextField(expr, { expr = it }, label = { Text(stringResource(R.string.true_expression)) }, modifier = Modifier.fillMaxWidth(),
                                colors = studioFieldColors())
                            Text(stringResource(R.string.true_expression_help), color = Am.OnDarkDim)
                            ToolAction(R.string.true_bake_expression, !busy) {
                                val ref = listOf(PropRef.Location, PropRef.Scale, PropRef.Rotation, PropRef.Opacity)[property]
                                apply { s -> s.update(selected!!) { Studio.expression(it, ref, expr, s.fps) } }
                            }
                        } else {
                            ToolAction(R.string.true_audio_title, !busy) { state.openAudioMotion(); onClose() }
                            ToolAction(R.string.true_path_title, !busy && state.selected?.kind != "audio") { state.openMotionPath(); onClose() }
                            ToolAction(R.string.true_expressions, !busy) { operation = 1 }
                            ToolAction(R.string.true_reverse_keys, !busy) { apply { s -> s.update(selected!!, change = Studio::reverseKeys) } }
                            ToolAction(R.string.true_copy_transform, !busy) { TransformClipboard.layer = state.scene.layer(selected!!); operation = 2 }
                            ToolAction(R.string.true_paste_transform, !busy && TransformClipboard.layer != null) {
                                val source = TransformClipboard.layer!!
                                apply { s -> s.update(selected!!) { Studio.pasteTransform(source, it, fit = true, relative = false) } }
                            }
                            ToolAction(R.string.true_paste_relative, !busy && TransformClipboard.layer != null) {
                                val source = TransformClipboard.layer!!
                                apply { s -> s.update(selected!!) { Studio.pasteTransform(source, it, fit = true, relative = true) } }
                            }
                            ToolAction(R.string.true_bop, !busy) { state.openAudioMotion(1); onClose() }
                        }
                    }
                    1 -> {
                        ToolAction(R.string.true_photo_title, !busy) { state.openFramePhoto() }
                        ToolAction(R.string.true_photo_video_action, !busy && state.selected?.mediaFill?.video == true) { state.openFramePhoto(true) }
                        ToolAction(R.string.true_particles_title, !busy) { state.addParticles(); onClose() }
                        ToolAction(R.string.true_vector_new, !busy) {
                            onClose()
                            app.truemotion.vector.VectorAdapter.open(studioActivity(context), state, true)
                        }
                        ToolAction(R.string.true_vector_edit, !busy && app.truemotion.vector.VectorAdapter.isVector(state.selected)) {
                            onClose()
                            app.truemotion.vector.VectorAdapter.open(studioActivity(context), state, false)
                        }
                        var count by remember { mutableStateOf("6") }
                        var columns by remember { mutableStateOf("3") }
                        var dx by remember { mutableStateOf("120") }
                        var dy by remember { mutableStateOf("120") }
                        var radius by remember { mutableStateOf("200") }
                        var stagger by remember { mutableStateOf("0") }
                        var ring by remember { mutableStateOf(false) }
                        Row { TextButton({ ring = false }) { Text(stringResource(R.string.true_grid), color = if (!ring) Am.Accent else Am.OnDarkDim) }
                            TextButton({ ring = true }) { Text(stringResource(R.string.true_ring), color = if (ring) Am.Accent else Am.OnDarkDim) } }
                        StudioNumber(R.string.true_count, count) { count = it }
                        if (ring) StudioNumber(R.string.true_radius, radius) { radius = it }
                        else {
                            StudioNumber(R.string.true_columns, columns) { columns = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { StudioNumber(R.string.true_spacing_x, dx) { dx = it } }
                                Box(Modifier.weight(1f)) { StudioNumber(R.string.true_spacing_y, dy) { dy = it } }
                            }
                        }
                        StudioNumber(R.string.true_stagger, stagger) { stagger = it }
                        ToolAction(R.string.true_create_array, !busy && selected != null) {
                            apply { s -> Studio.array(s, selected!!, count.toInt(), columns.toInt(), dx.toFloat(), dy.toFloat(), ring, radius.toFloat(), stagger.toFloat(), false) }
                        }
                        ToolAction(R.string.true_sequence, !busy && MultiSelect.ids.size > 1) {
                            val ids = MultiSelect.ids.toSet()
                            apply { s -> Studio.sequence(s, ids, stagger.toFloat()) }
                        }
                    }
                    2 -> {
                        Text(stringResource(R.string.true_grade_help), color = Am.OnDarkDim)
                        ToolAction(R.string.true_grade, !busy) {
                            state.openColorGrade()
                            onClose()
                        }
                    }
                    3 -> TruePluginsPanel(state)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Am.Accent)
                if (error != null) Text(stringResource(R.string.true_operation_error, error!!), color = Am.Warning)
                Text(stringResource(R.string.true_credits), color = Am.OnDarkDim, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton({ links.openUri("https://t.me/TrueMoti0n") }) { Text("True Motion", color = Am.Accent) }
                    TextButton({ links.openUri("https://t.me/Aftermotionex") }) { Text("Toji Motion", color = Am.Accent) }
                    TextButton({ links.openUri("https://github.com/Comett1225/AbsentMotion") }) { Text("AbsentMotion", color = Am.Accent) }
                }
            }
        }
    }
}

@Composable
private fun ToolAction(label: Int, enabled: Boolean, action: () -> Unit) {
    Button(action, Modifier.fillMaxWidth(), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Am.Button, contentColor = Am.OnDark)) { Text(stringResource(label)) }
}

@Composable
private fun studioFieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = Am.OnDark, unfocusedTextColor = Am.OnDark,
    focusedBorderColor = Am.Accent, unfocusedBorderColor = Am.Line, focusedLabelColor = Am.Accent, unfocusedLabelColor = Am.OnDarkDim)

@Composable
private fun StudioNumber(label: Int, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = studioFieldColors())
}

private fun studioActivity(context: android.content.Context): android.app.Activity {
    var current = context
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    error("Editor activity unavailable")
}
