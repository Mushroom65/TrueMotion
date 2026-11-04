package app.absentmotion.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am

/** Part of the layer inspector: selection, settings and errors stay beside the timeline. */
@Composable
internal fun MotionToolsPanel(state: EditorState, modifier: Modifier = Modifier) {
    val layer = state.selected ?: return
    SideEffect { state.keyTarget = null; state.easingOpen = false }
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    fun edit(action: (Scene) -> Scene) {
        try { state.edit(change = action); error = null } catch (e: IllegalArgumentException) { error = e.message }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            TextButton({ state.panel = EditorPanel.Tools }) { Text("‹", color = Am.Accent) }
            listOf(R.string.motion_copies, R.string.motion_links, R.string.depth_scene).forEachIndexed { index, title ->
                TextButton({ tab = index; error = null }) { Text(stringResource(title), color = if (tab == index) Am.Accent else Am.OnDarkDim) }
            }
        }
        if (tab == 2) {
            if (state.groupPath.isNotEmpty()) Text(stringResource(R.string.depth_root_only), color = Am.OnDarkDim)
            else {
                val enabled = state.scene.node["trueDepth3D"] == "true"
                Row {
                    Switch(enabled, { on -> edit { Scene(it.node.with("trueDepth3D", if (on) "true" else null)) } })
                    Text(stringResource(R.string.depth_enable), Modifier.padding(10.dp), color = Am.OnDark)
                }
                Text(stringResource(R.string.depth_help), color = Am.OnDarkDim, fontSize = 11.sp)
                Text(stringResource(R.string.depth_light), color = Am.Accent)
                fun value(name: String, fallback: Float) = state.scene.node[name]?.toFloatOrNull() ?: fallback
                fun light(name: String, v: Float) = edit { Scene(it.node.with(name, "$v")) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionNumber(stringResource(R.string.depth_azimuth), value("trueLightAzimuth",135f), Modifier.weight(1f)) { light("trueLightAzimuth",it) }
                    MotionNumber(stringResource(R.string.depth_elevation), value("trueLightElevation",50f), Modifier.weight(1f)) { light("trueLightElevation",it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionNumber(stringResource(R.string.model_param_light_intensity), value("trueLightIntensity",1f), Modifier.weight(1f)) { light("trueLightIntensity",it.coerceIn(0f,4f)) }
                    MotionNumber(stringResource(R.string.model_param_ambient_light), value("trueLightAmbient",.35f), Modifier.weight(1f)) { light("trueLightAmbient",it.coerceIn(0f,1f)) }
                }
            }
        } else if (tab == 0) {
            val sourceId = layer.copySource ?: layer.id
            val source = state.scene.layer(sourceId) ?: layer
            val copies = state.scene.repeatedCopies(sourceId)
            Text(stringResource(R.string.motion_copy_help), color = Am.OnDarkDim, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ state.selectedId = sourceId }) { Text("0 · ${source.label}", color = if (layer.id == sourceId) Am.Accent else Am.OnDark) }
                copies.forEachIndexed { index, copy -> TextButton({ state.selectedId = copy.id }) {
                    Text("${index + 1}", color = if (layer.id == copy.id) Am.Accent else Am.OnDark)
                } }
                TextButton({ edit { it.withRepeatedCopies(sourceId, copies.size + 2) } }, enabled = copies.size < 199) { Text("+", color = Am.Accent) }
            }
            val offset = layer.copyOffset
            if (offset == null) {
                MotionNumber(stringResource(R.string.motion_count), (copies.size + 1).toFloat()) { n ->
                    if (n.toInt() in 1..200) edit { it.withRepeatedCopies(sourceId, n.toInt()) }
                }
            } else {
                fun update(value: CopyOffset) = edit { it.withCopyOffset(layer.id, value) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionNumber("X", offset.x, Modifier.weight(1f)) { update(offset.copy(x = it)) }
                    MotionNumber("Y", offset.y, Modifier.weight(1f)) { update(offset.copy(y = it)) }
                    MotionNumber("Z", offset.z, Modifier.weight(1f)) { update(offset.copy(z = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionNumber(stringResource(R.string.true_rotation), offset.rotation, Modifier.weight(1f)) { update(offset.copy(rotation = it)) }
                    MotionNumber(stringResource(R.string.true_stagger), offset.delayFrames, Modifier.weight(1f)) { update(offset.copy(delayFrames = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionNumber("Scale X ×", offset.scaleX, Modifier.weight(1f)) { update(offset.copy(scaleX = it)) }
                    MotionNumber("Scale Y ×", offset.scaleY, Modifier.weight(1f)) { update(offset.copy(scaleY = it)) }
                    MotionNumber("α ×", offset.opacity, Modifier.weight(1f)) { if (it in 0f..1f) update(offset.copy(opacity = it)) }
                }
                TextButton({ edit { it.detachRepeatedCopy(layer.id) } }) { Text(stringResource(R.string.motion_detach), color = Am.Accent) }
            }
        } else if (layer.copySource != null) {
            Text(stringResource(R.string.motion_link_original), color = Am.OnDarkDim)
            TextButton({ state.selectedId = layer.copySource }) { Text(stringResource(R.string.motion_go_original), color = Am.Accent) }
        } else {
            val targets = layer.numericChannels()
            var target by remember(layer.id) { mutableStateOf(targets.first()) }
            if (target !in targets) target = targets.first()
            val current = state.scene.resolvedValueLink(layer.id, target)
            val sources = state.scene.layers.filter { it.copySource == null }
            var sourceId by remember(layer.id, target, current) { mutableStateOf(current?.source ?: sources.firstOrNull { it.id != layer.id }?.id ?: layer.id) }
            val source = state.scene.layer(sourceId) ?: layer
            val channels = source.numericChannels()
            var channel by remember(layer.id, target, sourceId, current) { mutableStateOf(current?.channel?.takeIf { it in channels } ?: channels.first()) }
            var multiply by remember(layer.id, target, current) { mutableFloatStateOf(current?.multiply ?: 1f) }
            var offset by remember(layer.id, target, current) { mutableFloatStateOf(current?.offset ?: 0f) }
            var delay by remember(layer.id, target, current) { mutableFloatStateOf((current?.delayMs ?: 0) * state.scene.fps / 1000f) }
            Text(stringResource(R.string.motion_link_help), color = Am.OnDarkDim, fontSize = 11.sp)
            MotionChoice(stringResource(R.string.motion_target), targets, target, ::channelTitle) { target = it }
            MotionChoice(stringResource(R.string.motion_source), sources, source, { it.label.ifBlank { "#${it.id}" } }) { sourceId = it.id }
            MotionChoice(stringResource(R.string.motion_channel), channels, channel, ::channelTitle) { channel = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MotionNumber("×", multiply, Modifier.weight(1f)) { multiply = it }
                MotionNumber("+", offset, Modifier.weight(1f)) { offset = it }
                MotionNumber(stringResource(R.string.motion_delay), delay, Modifier.weight(1f)) { delay = it }
            }
            Row {
                TextButton({ edit { it.withValueLink(layer.id, target, ValueLink(source.id, channel, multiply, offset, (delay * 1000 / state.scene.fps).toInt())) } }) {
                    Text(stringResource(R.string.motion_link_apply), color = Am.Accent)
                }
                if (current != null) TextButton({ edit { it.withoutValueLink(layer.id, target) } }) { Text(stringResource(R.string.motion_unlink), color = Am.Accent) }
            }
            if (current != null) Text("↪ ${state.scene.layer(current.source)?.label.orEmpty()} · ${channelTitle(current.channel)} × ${current.multiply} + ${current.offset}", color = Am.Accent, fontSize = 11.sp)
        }
        error?.let { Text(it, color = Am.Warning, fontSize = 12.sp) }
    }
}

@Composable
private fun <T> MotionChoice(label: String, values: List<T>, selected: T, title: (T) -> String, choose: (T) -> Unit) {
    Text(label, color = Am.OnDarkDim, fontSize = 11.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { values.forEach { value ->
        TextButton({ choose(value) }) { Text(title(value), color = if (value == selected) Am.Accent else Am.OnDark, fontSize = 11.sp) }
    } }
}

private fun channelTitle(channel: ValueChannel): String {
    val prefix = if (channel.ref.kind == PropRef.Kind.Effect) "FX ${channel.ref.effectIndex + 1} · " else ""
    val axis = if (channel.ref in listOf(PropRef.Location, PropRef.Scale, PropRef.Pivot, PropRef.Tilt, PropRef.Skew)) " ${listOf("X", "Y", "Z").getOrElse(channel.axis) { "${channel.axis + 1}" }}" else if (channel.axis > 0) " ${channel.axis + 1}" else ""
    val name = when (channel.ref) {
        PropRef.Location -> app.absentmotion.i18n.AppText.get(R.string.true_position)
        PropRef.Rotation -> app.absentmotion.i18n.AppText.get(R.string.true_rotation)
        PropRef.Scale -> app.absentmotion.i18n.AppText.get(R.string.true_scale)
        PropRef.Opacity -> app.absentmotion.i18n.AppText.get(R.string.true_opacity)
        else -> channel.ref.name
    }
    return prefix + name + axis
}

@Composable
internal fun MotionNumber(label: String, value: Float, modifier: Modifier = Modifier, change: (Float) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
    OutlinedTextField(text, { text = it; it.toFloatOrNull()?.takeIf(Float::isFinite)?.let(change) },
        modifier = modifier.onFocusChanged { focused = it.isFocused }, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Am.OnDark, unfocusedTextColor = Am.OnDark, focusedBorderColor = Am.Accent, unfocusedBorderColor = Am.OnDarkDim))
}
