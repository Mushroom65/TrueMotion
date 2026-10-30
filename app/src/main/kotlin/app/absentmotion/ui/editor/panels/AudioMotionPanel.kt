package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.truemotion.AudioMotion
import app.absentmotion.truemotion.AudioReactive
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val SettingsSaver = listSaver<AudioMotion.Settings, String>(
    save = { it.attrs().values.toList() }, restore = { AudioMotion.Settings.read(AudioMotion.Settings().attrs().keys.zip(it).toMap()) },
)
private data class ReadyResponse(val key: List<Any?>, val response: AudioEnvelope.Response)

@Composable
internal fun AudioMotionPanel(state: EditorState, modifier: Modifier) {
    val layer = state.selectedId?.let(state.scene::layer) ?: return
    val sources = state.scene.layers.filter { it.soundUri != null }
    fun hasSound(node: XmlNode): Boolean = (node.name in Scene.LAYER_TAGS && Layer(node).soundUri != null) || node.children.any(::hasSound)
    val soundAvailable = remember(state.scene) { hasSound(state.scene.node) }
    val visual = layer.kind !in setOf("audio", "camera")
    val editingEffect = layer.effects.getOrNull(state.effectIndex)?.takeIf { it["id"] == AudioReactive.ID }
    val editedIndex = state.effectIndex.takeIf { editingEffect != null }
    var settings by rememberSaveable(layer.id, stateSaver = SettingsSaver) {
        val stored = AudioMotion.Property.entries.firstNotNullOfOrNull { AudioMotion.binding(layer, it) }
        mutableStateOf(editingEffect?.let(AudioReactive::settings) ?: stored?.let { AudioMotion.Settings.read(it.attrs) } ?: AudioMotion.Settings(amount = if (state.audioMotionTab == 1) .2f else .3f, source = layer.id.takeIf { layer.soundUri != null } ?: sources.firstOrNull()?.id))
    }
    var liveEffect by rememberSaveable(layer.id) { mutableStateOf(true) }
    var tab by rememberSaveable(layer.id, state.audioMotionTab) { mutableIntStateOf(if (!visual) 2 else if (state.audioMotionTab == 1 || settings.mode == AudioMotion.Mode.Bop) 1 else 0) }
    var profile by remember(layer.id) { mutableStateOf<AudioMotion.Profile?>(null) }
    var job by remember(layer.id) { mutableStateOf<Job?>(null) }
    var busy by remember(layer.id) { mutableStateOf(false) }
    var progress by remember(layer.id) { mutableFloatStateOf(0f) }
    var error by remember(layer.id) { mutableStateOf<String?>(null) }
    var note by remember(layer.id) { mutableStateOf<String?>(null) }
    var manual by rememberSaveable(layer.id) { mutableStateOf(false) }
    var bpm by rememberSaveable(layer.id) { mutableFloatStateOf(120f) }
    var previewOn by rememberSaveable(layer.id) { mutableStateOf(true) }
    var previewBase by remember(layer.id) { mutableStateOf(state.scene) }
    val previewOwner = remember(layer.id) { Any() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signature = remember(state.scene, settings.source, settings.respectVolume) { AudioMotion.signature(state.scene, settings.source, settings.respectVolume) }
    val current = profile?.takeIf { it.signature == signature }
    val responseKey = listOf(current?.data, settings.band, settings.gain, settings.gate, settings.attackMs, settings.releaseMs, settings.normalize, settings.invert)
    val responding = settings
    val ready by produceState<ReadyResponse?>(null, responseKey) {
        val data = current?.data
        value = data?.let { withContext(Dispatchers.Default) {
            ReadyResponse(responseKey, it.response(responding.band, responding.gain, responding.gate, responding.attackMs, responding.releaseMs, responding.normalize, responding.invert))
        } }
    }
    val response = ready?.takeIf { it.key == responseKey }?.response
    val ref = settings.property.ref
    val target = KeyTarget(ref, settings.property.default)
    SideEffect { state.keyTarget = if (visual) target else null; state.pointAdjust = null }
    DisposableEffect(layer.id) { onDispose { job?.cancel(); state.clearVisualPreview(previewOwner); state.endGesture() } }
    LaunchedEffect(signature) { if (profile?.signature != signature) profile = null }
    LaunchedEffect(state.scene) {
        // Undo and other real edits must remain visible, even while this panel is open.
        if (previewBase != state.scene) { previewOn = false; previewBase = state.scene }
    }
    if (state.easingOpen) { EasingPanel(state, modifier); return }
    val useEffect = liveEffect && tab == 0
    val renderStatus by state.engine.audioEffects.collectAsState()
    LaunchedEffect(previewOn, state.scene, settings, response, tab, liveEffect) {
        if (!previewOn || !visual || tab == 2 || tab == 0 && !useEffect && current == null || tab == 1 && state.scene.bookmarks.isEmpty()) {
            state.clearVisualPreview(previewOwner)
            return@LaunchedEffect
        }
        if (tab == 0 && !useEffect && response == null) return@LaunchedEffect
        val before = state.scene
        val config = settings.copy(mode = if (tab == 1) AudioMotion.Mode.Bop else AudioMotion.Mode.Envelope,
            property = if (tab == 1) AudioMotion.Property.Scale else settings.property)
        if (!useEffect && AudioMotion.keyCount(layer, before.fps, config.keyRate) > AudioMotion.MAX_KEYS) {
            state.clearVisualPreview(previewOwner)
            return@LaunchedEffect
        }
        delay(180)
        try {
            val shown = withContext(Dispatchers.Default) {
                val next = before.update(layer.id) {
                    if (useEffect) AudioReactive.put(it, config, editedIndex ?: AudioReactive.index(it, config.property))
                    else AudioMotion.bake(before, if (editedIndex != null) it.removeEffect(editedIndex) else AudioReactive.remove(it, config.property), config, response)
                }
                next to next.toXml()
            }
            state.previewVisuals(previewOwner, before, shown.first, shown.second)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.clearVisualPreview(previewOwner); error = e.message ?: context.getString(R.string.true_audio_unreadable) }
    }

    fun number(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
        state.keypad = KeypadRequest(listOf(KeypadField(label, value)), 0) { values ->
            if (values[0].isFinite()) change(values[0].coerceIn(range))
        }
    }
    fun analyze(force: Boolean = true) {
        if (busy) return
        state.playing = false
        val before = state.scene
        val source = settings.source; val volume = settings.respectVolume
        busy = true; progress = 0f; error = null; note = null
        job = scope.launch {
            try {
                val result = state.engine.audioEffectProfile(before, source, volume, force) { progress = it }
                check(AudioMotion.signature(state.scene, source, volume) == result.signature) { context.getString(R.string.true_audio_changed) }
                profile = result
                if (force) state.engine.refreshAudioEffects()
                if (result.data.peak(AudioEnvelope.Band.Full) < 1e-6f) note = context.getString(R.string.true_audio_silent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: context.getString(R.string.true_audio_unreadable) }
            finally { busy = false }
        }
    }
    LaunchedEffect(signature, useEffect) {
        if (useEffect && soundAvailable) analyze(force = false)
    }
    fun apply() {
        if (busy) return
        state.playing = false; state.endGesture()
        val before = state.scene
        val chosen = before.layer(layer.id) ?: return
        val config = settings.copy(mode = if (tab == 1) AudioMotion.Mode.Bop else AudioMotion.Mode.Envelope,
            property = if (tab == 1) AudioMotion.Property.Scale else settings.property)
        val sampled = response
        val analyzed = current
        if (!useEffect && AudioMotion.keyCount(chosen, before.fps, config.keyRate) > AudioMotion.MAX_KEYS) {
            error = context.getString(R.string.true_audio_many_keys); return
        }
        busy = true; error = null; note = null
        job = scope.launch {
            try {
                if (!useEffect && config.mode == AudioMotion.Mode.Envelope) {
                    check(analyzed != null && sampled != null) { context.getString(R.string.true_audio_analyze_first) }
                    check(analyzed.sourcesUnchanged(context)) { context.getString(R.string.true_audio_changed) }
                }
                val result = withContext(Dispatchers.Default) { before.update(chosen.id) {
                    if (useEffect) AudioReactive.put(it, config, editedIndex ?: AudioReactive.index(it, config.property))
                    else AudioMotion.bake(before, if (editedIndex != null) it.removeEffect(editedIndex) else AudioReactive.remove(it, config.property), config, sampled)
                } }
                check(state.scene == before) { context.getString(R.string.true_audio_changed) }
                state.edit { result }
                if (useEffect) {
                    state.effectIndex = editedIndex ?: AudioReactive.index(result.layer(chosen.id)!!, config.property)
                    note = context.getString(R.string.true_audio_effect_applied)
                } else note = context.getString(R.string.true_audio_applied, result.layer(chosen.id)!!.keyframes(config.property.ref).size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: context.getString(R.string.true_audio_unreadable) }
            finally { busy = false }
        }
    }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            AudioIcon(PanelIcons.Back, stringResource(R.string.topbar_back)) { state.stepBack() }
            Text(stringResource(R.string.true_audio_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            if (visual) AudioIcon(PanelIcons.Easing, stringResource(R.string.a11y_easing)) {
                state.clearVisualPreview(previewOwner)
                if (state.selected?.easing(ref) != null) state.easingOpen = true else state.easingHint = true
            }
            if (busy) TextButton({ job?.cancel(); previewOn = false }) { Text(stringResource(R.string.cancel), color = Am.Accent) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.true_audio_react, R.string.true_bop, R.string.true_audio_beats).forEachIndexed { i, title ->
                AudioChip(stringResource(title), tab == i, Modifier.weight(1f), !busy && (visual || i == 2)) {
                    tab = i; error = null; note = null; previewOn = true
                    if (i == 1) settings = settings.copy(property = AudioMotion.Property.Scale, amount = if (settings.property == AudioMotion.Property.Scale) settings.amount else .2f)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visual && tab != 2) AudioCheck(stringResource(R.string.true_audio_live_preview), previewOn, !busy) { previewOn = it }
            if (visual && tab == 0) AudioCheck(stringResource(R.string.true_audio_effect_mode), liveEffect, !busy) { liveEffect = it }
            if (useEffect && renderStatus.pending) Text(stringResource(R.string.true_audio_effect_loading), color = Am.OnDarkDim, fontSize = 12.sp)
            if (useEffect && renderStatus.error != null) Text(renderStatus.error!!, color = Am.Warning, fontSize = 12.sp)
            if (tab != 1) {
                SourcePicker(sources, settings.source, !busy) { settings = settings.copy(source = it) }
                if (settings.source != null) AudioCheck(stringResource(R.string.true_audio_respect_volume), settings.respectVolume, !busy) { settings = settings.copy(respectVolume = it) }
                Button({ analyze() }, Modifier.fillMaxWidth(), enabled = !busy && soundAvailable, colors = audioButtonColors()) {
                    Text(stringResource(if (current == null) R.string.true_audio_analyze else R.string.true_audio_analyze_again))
                }
                if (!soundAvailable) Text(stringResource(R.string.true_audio_no_source), color = Am.OnDarkDim, fontSize = 12.sp)
            }
            if (busy) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = Am.Accent, trackColor = Am.Field)
            if (error != null) Text(error!!, color = Am.Warning, fontSize = 12.sp)
            if (note != null) Text(note!!, color = Am.Accent, fontSize = 12.sp)
            if (tab == 0) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioMotion.Property.entries.forEach { property -> AudioChip(stringResource(propertyLabel(property)), settings.property == property, enabled = !busy) {
                        settings = AudioMotion.binding(layer, property)?.let { AudioMotion.Settings.read(it.attrs) }
                            ?: settings.copy(property = property, amount = property.amount)
                    } }
                }
                if (response != null) EnvelopeGraph(state, layer.startTime, layer.endTime, { response.at(it - settings.offsetMs) })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioEnvelope.Band.entries.forEachIndexed { i, band -> AudioChip(stringResource(listOf(R.string.true_audio_full_band, R.string.true_audio_bass, R.string.true_audio_mid, R.string.true_audio_high)[i]), settings.band == band, enabled = !busy) { settings = settings.copy(band = band) } }
                }
                AudioNumber(stringResource(R.string.true_audio_amount), settings.amount * if (settings.property == AudioMotion.Property.Scale || settings.property == AudioMotion.Property.Opacity) 100 else 1,
                    (settings.property.minimum * if (settings.property == AudioMotion.Property.Scale || settings.property == AudioMotion.Property.Opacity) 100 else 1)..(settings.property.maximum * if (settings.property == AudioMotion.Property.Scale || settings.property == AudioMotion.Property.Opacity) 100 else 1), !busy,
                    number = ::number) { settings = settings.copy(amount = it / if (settings.property == AudioMotion.Property.Scale || settings.property == AudioMotion.Property.Opacity) 100 else 1) }
                AudioNumber(stringResource(R.string.true_audio_gain), settings.gain, 0f..8f, !busy, ::number) { settings = settings.copy(gain = it) }
                AudioNumber(stringResource(R.string.true_audio_gate), settings.gate, 0f..0.95f, !busy, ::number) { settings = settings.copy(gate = it) }
                AudioNumber(stringResource(R.string.true_audio_attack), settings.attackMs, 0f..1000f, !busy, ::number) { settings = settings.copy(attackMs = it) }
                AudioNumber(stringResource(R.string.true_audio_release), settings.releaseMs, 0f..3000f, !busy, ::number) { settings = settings.copy(releaseMs = it) }
                AudioCheck(stringResource(R.string.true_audio_normalize), settings.normalize, !busy) { settings = settings.copy(normalize = it) }
                AudioCheck(stringResource(R.string.true_audio_invert), settings.invert, !busy) { settings = settings.copy(invert = it) }
                Text(stringResource(if (settings.property == AudioMotion.Property.Opacity) R.string.true_audio_opacity_help else R.string.true_audio_property_help), color = Am.OnDarkDim, fontSize = 11.sp)
            } else if (tab == 1) {
                Text(stringResource(R.string.true_audio_marker_count, state.scene.bookmarks.size), color = Am.OnDark, fontSize = 13.sp)
                TextButton({ tab = 2 }, enabled = !busy) { Text(stringResource(R.string.true_audio_open_beats), color = Am.Accent) }
                val beats = state.scene.bookmarks.sorted()
                EnvelopeGraph(state, layer.startTime, layer.endTime, { AudioMotion.pulse(beats, it - settings.offsetMs, settings.decayMs) })
                AudioNumber(stringResource(R.string.true_audio_amount_percent), settings.amount * 100, -100f..500f, !busy, ::number) { settings = settings.copy(amount = it / 100) }
                AudioNumber(stringResource(R.string.true_audio_decay), settings.decayMs, 10f..5000f, !busy, ::number) { settings = settings.copy(decayMs = it) }
            } else {
                if (current != null) {
                    val gaps = current.beats.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
                    val estimate = if (gaps.isEmpty()) "—" else String.format(Locale.US, "%.1f", 60_000.0 / gaps[gaps.size / 2])
                    Text(stringResource(R.string.true_audio_beat_result, current.beats.size, estimate), color = Am.OnDark, fontSize = 13.sp)
                    val raw = current.data
                    val maximum = raw.peak(AudioEnvelope.Band.Full).coerceAtLeast(1e-6f)
                    val shape = remember(raw) { AudioEnvelope.Response(raw.startMs, raw.stepMs, FloatArray(raw.count) { raw.bands[0][it] / maximum }) }
                    EnvelopeGraph(state, raw.startMs, (raw.startMs + raw.count * raw.stepMs).toInt(), shape::at)
                    Button({
                        val before = state.scene
                        state.edit { AudioMotion.addMarkers(it, current.beats) }
                        note = context.getString(R.string.true_audio_markers_added, state.scene.bookmarks.size - before.bookmarks.size)
                    }, Modifier.fillMaxWidth(), enabled = !busy && current.beats.isNotEmpty(), colors = audioButtonColors()) { Text(stringResource(R.string.true_audio_add_detected)) }
                }
                AudioCheck(stringResource(R.string.true_audio_manual_grid), manual, !busy) { manual = it }
                if (manual) {
                    AudioNumber(stringResource(R.string.true_audio_bpm), bpm, 20f..400f, !busy, ::number) { bpm = it }
                    AudioNumber(stringResource(R.string.true_audio_offset), settings.offsetMs, -3000f..3000f, !busy, ::number) { settings = settings.copy(offsetMs = it) }
                    Text(stringResource(R.string.true_audio_grid_help), color = Am.OnDarkDim, fontSize = 11.sp)
                    Button({
                        val grid = AudioMotion.grid(layer.startTime.coerceAtLeast(0), minOf(layer.endTime + 1, state.scene.totalTime + 1), bpm, settings.offsetMs)
                        val before = state.scene
                        state.edit { AudioMotion.addMarkers(it, grid) }
                        note = context.getString(R.string.true_audio_markers_added, state.scene.bookmarks.size - before.bookmarks.size)
                    }, Modifier.fillMaxWidth(), enabled = !busy, colors = audioButtonColors()) { Text(stringResource(R.string.true_audio_add_grid)) }
                }
            }
            if (tab != 2) {
                AudioNumber(stringResource(R.string.true_audio_offset), settings.offsetMs, -3000f..3000f, !busy, ::number) { settings = settings.copy(offsetMs = it) }
                if (!useEffect) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 30, 15, 5).forEach { rate -> AudioChip(if (rate == 0) stringResource(R.string.true_audio_every_frame) else stringResource(R.string.true_audio_key_rate, rate), settings.keyRate == rate, enabled = !busy) { settings = settings.copy(keyRate = rate) } }
                }
                if (!useEffect) Text(stringResource(R.string.true_audio_key_count, AudioMotion.keyCount(layer, state.scene.fps, settings.keyRate)), color = Am.OnDarkDim, fontSize = 11.sp)
                Button({ apply() }, Modifier.fillMaxWidth(), enabled = !busy && (if (useEffect) soundAvailable && current != null else if (tab == 1) state.scene.bookmarks.isNotEmpty() else response != null), colors = audioButtonColors()) { Text(stringResource(R.string.true_audio_apply)) }
                Text(stringResource(if (useEffect) R.string.true_audio_effect_help else R.string.true_audio_bake_help), color = Am.OnDarkDim, fontSize = 11.sp)
                if (useEffect && editingEffect != null) {
                    AudioCheck(stringResource(R.string.true_audio_effect_enabled), editingEffect["hidden"] != "true", !busy) { on ->
                        state.editSelected { it.withEffect(state.effectIndex) { effect -> effect.with("hidden", (!on).toString()) } }
                    }
                    TextButton({ state.editSelected { it.removeEffect(state.effectIndex) }; state.effectIndex = -1 }, enabled = !busy) {
                        Text(stringResource(R.string.true_audio_effect_remove), color = Am.Warning)
                    }
                }
                val property = if (tab == 1) AudioMotion.Property.Scale else settings.property
                if (AudioMotion.binding(layer, property) != null) {
                    TextButton({ state.editSelected { AudioMotion.restore(it, property) } }, enabled = !busy) { Text(stringResource(R.string.true_audio_restore), color = Am.Accent) }
                    TextButton({ state.editSelected { AudioMotion.detach(it, property) } }, enabled = !busy) { Text(stringResource(R.string.true_audio_detach), color = Am.OnDarkDim) }
                }
            }
        }
    }
}

@Composable
private fun AudioIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(onClick = action), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp), tint = Am.OnDark) }
}

@Composable
private fun AudioChip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Am.Chosen else Am.Field)
        .clickable(enabled, onClick = action).semantics { contentDescription = label }.padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (!enabled) Am.Inactive else if (selected) Am.Accent else Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SourcePicker(sources: List<Layer>, selected: Long?, enabled: Boolean, change: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = sources.firstOrNull { it.id == selected }
    Box(Modifier.fillMaxWidth()) {
        AudioChip(stringResource(R.string.true_audio_source, chosen?.label ?: if (selected == null) stringResource(R.string.true_audio_project_mix) else "#$selected"), false, Modifier.fillMaxWidth(), enabled) { expanded = true }
        DropdownMenu(expanded, { expanded = false }, containerColor = Am.Field) {
            DropdownMenuItem({ Text(stringResource(R.string.true_audio_project_mix), color = Am.OnDark) }, { change(null); expanded = false })
            sources.forEach { layer -> DropdownMenuItem({ Text(layer.label, color = Am.OnDark) }, { change(layer.id); expanded = false }) }
        }
    }
}

@Composable
private fun AudioCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled) { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Am.Accent, uncheckedColor = Am.OnDarkDim, checkmarkColor = Am.OnAccent))
        Text(label, Modifier.weight(1f).padding(start = 8.dp), color = Am.OnDark, fontSize = 12.sp)
    }
}

@Composable
private fun AudioNumber(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    number: (String, Float, ClosedFloatingPointRange<Float>, (Float) -> Unit) -> Unit, change: (Float) -> Unit) {
    val enter = stringResource(R.string.true_grade_enter_value, label)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Am.Field).padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Am.OnDark, fontSize = 12.sp)
            Text(String.format(Locale.US, "%.2f", value), Modifier.clip(RoundedCornerShape(5.dp)).background(Am.Button).clickable(enabled) { number(label, value, range, change) }
                .semantics { contentDescription = enter }.padding(horizontal = 10.dp, vertical = 6.dp), color = Am.Accent, fontSize = 12.sp)
        }
        Slider(value.coerceIn(range), change, Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = label }, enabled = enabled,
            valueRange = range, colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent, inactiveTrackColor = Am.Line))
    }
}

@Composable
private fun EnvelopeGraph(state: EditorState, start: Int, end: Int, sample: (Double) -> Float) {
    val accent = Am.Accent; val grid = Am.Line; val ink = Am.OnDark; val span = (end - start).coerceAtLeast(1)
    val description = stringResource(R.string.true_audio_envelope_graph)
    Canvas(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(8.dp)).background(Am.Field).semantics { contentDescription = description }) {
        val inset = 8.dp.toPx(); val width = size.width - 2 * inset; val height = size.height - 2 * inset
        for (i in 0..4) drawLine(grid, Offset(inset, inset + height * i / 4), Offset(inset + width, inset + height * i / 4), 1.dp.toPx())
        val path = Path()
        for (i in 0..160) {
            val x = inset + width * i / 160
            val y = inset + height * (1 - sample(start + span * i / 160.0).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(2.dp.toPx()))
        val time = state.timeMs
        if (time in start..end) {
            val x = inset + width * (time - start) / span
            drawLine(ink, Offset(x, inset), Offset(x, inset + height), 1.dp.toPx())
        }
    }
}

@Composable private fun audioButtonColors() = ButtonDefaults.buttonColors(containerColor = Am.Button, contentColor = Am.Accent, disabledContainerColor = Am.Field, disabledContentColor = Am.Inactive)
private fun propertyLabel(property: AudioMotion.Property): Int = when (property) {
    AudioMotion.Property.Scale -> R.string.true_scale; AudioMotion.Property.Rotation -> R.string.true_rotation
    AudioMotion.Property.Y -> R.string.true_audio_position_y; AudioMotion.Property.Opacity -> R.string.true_opacity
}
