package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.truemotion.ColorGrade
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.effects.EffectIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import java.util.Locale
import kotlin.math.*

/** Native layer panel: edits the scene through EditorState, with the live preview still visible. */
@Composable
internal fun ColorGradePanel(state: EditorState, index: Int, definition: EffectDefinition, modifier: Modifier) {
    val layer = state.selected ?: return
    val instance = layer.effects.getOrNull(index) ?: return
    var tab by rememberSaveable(layer.id, index) { mutableIntStateOf(0) }
    var tone by rememberSaveable(layer.id, index) { mutableStateOf("lift") }
    var channel by rememberSaveable(layer.id, index) { mutableStateOf("Y") }
    var band by rememberSaveable(layer.id, index) { mutableStateOf("Red") }
    var active by rememberSaveable(layer.id, index) { mutableStateOf("exposure") }
    var menu by remember { mutableStateOf(false) }
    val param = definition.params.first { it.id == active }
    val target = KeyTarget(PropRef.effect(index, active), param.missingDefault, param.type)
    val section = when (tab) {
        0 -> ColorGrade.primary; 1 -> ColorGrade.wheelKeys(tone)
        2 -> ColorGrade.curveKeys(channel); 3 -> ColorGrade.hslKeys(band); else -> listOf("gradeMix")
    }
    fun value(id: String) = ColorGrade.value(layer, index, definition, id)
    fun change(values: Map<String, Float>, gesture: Boolean = true) {
        state.playing = false
        state.editSelected(if (gesture) "grade-$index" else null) { ColorGrade.change(it, index, definition, values) }
    }
    fun activate(id: String) { active = id }
    fun numeric(id: String, label: String) {
        state.endGesture()
        activate(id)
        state.keypad = KeypadRequest(listOf(KeypadField(label, value(id))), 0) { numbers -> change(mapOf(id to numbers[0]), false) }
    }
    SideEffect { state.keyTarget = target; state.pointAdjust = null }
    DisposableEffect(layer.id, index) { onDispose { state.endGesture() } }
    if (state.easingOpen) { EasingPanel(state, modifier); return }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            GradeIcon(PanelIcons.Back, stringResource(R.string.topbar_back)) { state.endGesture(); state.stepBack() }
            Text(stringResource(R.string.true_grade_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            GradeIcon(PanelIcons.Easing, stringResource(R.string.a11y_easing)) {
                state.endGesture()
                if (layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true
            }
            val enabled = instance["hidden"] != "true"
            GradeIcon(if (enabled) EffectIcons.Eye else EffectIcons.EyeOff, stringResource(if (enabled) R.string.true_grade_disable else R.string.true_grade_enable), enabled) {
                state.endGesture()
                state.editSelected { it.withEffect(index) { effect -> effect.with("hidden", if (enabled) "true" else null) } }
            }
            GradeIcon(ChromeIcons.Undo, stringResource(R.string.true_grade_reset_section)) {
                state.endGesture()
                state.editSelected { ColorGrade.reset(it, index, definition, section) }
            }
            Box {
                GradeIcon(PanelIcons.More, stringResource(R.string.effects_more)) { state.endGesture(); menu = true }
                Presence(menu) {
                    MenuPopup(DropOrRise, { menu = false }) {
                        MenuRow(stringResource(R.string.effects_copy_effect)) { EffectClipboard.copyOne(instance); menu = false }
                        val copied = EffectClipboard.effects.singleOrNull()?.takeIf { it["id"] == ColorGrade.ID }
                        MenuRow(stringResource(R.string.effects_paste_values), enabled = copied != null) {
                            copied?.let { from -> state.editSelected { it.withEffect(index) { old -> old.copy(children = from.children) } } }
                            menu = false
                        }
                        MenuRow(stringResource(R.string.effects_duplicate)) {
                            state.editSelected { it.duplicateEffect(index) }; state.effectIndex = index + 1; menu = false
                        }
                        MenuRow(stringResource(R.string.effects_reset_defaults), divider = true) {
                            state.editSelected { it.withEffect(index) { old -> old.copy(children = definition.instance().children) } }; menu = false
                        }
                        MenuRow(stringResource(R.string.delete), divider = true) {
                            state.editSelected { it.removeEffect(index) }; state.effectIndex = -1; menu = false
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.true_grade_primary, R.string.true_grade_wheels, R.string.true_grade_curves, R.string.true_grade_hsl, R.string.true_grade_output).forEachIndexed { i, label ->
                GradeChip(stringResource(label), tab == i) {
                    state.endGesture(); tab = i
                    active = when (i) { 0 -> "exposure"; 1 -> tone; 2 -> "curve${channel}2"; 3 -> "hsl${band}H"; else -> "gradeMix" }
                }
            }
        }
        key(tab) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (tab) {
                    1 -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ColorGrade.tones.forEachIndexed { i, id ->
                                GradeChip(stringResource(listOf(R.string.true_grade_lift, R.string.true_grade_gamma, R.string.true_grade_gain)[i]), tone == id, Modifier.weight(1f)) {
                                    state.endGesture(); tone = id; activate(id)
                                }
                            }
                        }
                        val rgb = floatArrayOf(value("${tone}R"), value("${tone}G"), value("${tone}B"))
                        val wheelDescription = stringResource(R.string.true_grade_wheel_control, stringResource(toneLabel(tone)))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            GradeWheel(rgb, wheelDescription, Modifier.weight(1f).height(132.dp), {
                                activate("${tone}R")
                                change(mapOf("${tone}R" to it[0], "${tone}G" to it[1], "${tone}B" to it[2]))
                            }, state::endGesture)
                            Column(Modifier.width(108.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.true_grade_wheel_hint), color = Am.OnDarkDim, fontSize = 11.sp)
                                val refs = "RGB".map { PropRef.effect(index, "$tone$it") }
                                val keyed = refs.all { layer.onKeyframe(it) }
                                GradeChip(stringResource(R.string.true_grade_wheel_key), keyed) {
                                    state.endGesture()
                                    activate("${tone}R")
                                    state.editSelected { current ->
                                        refs.fold(current) { next, ref -> if (next.onKeyframe(ref) == keyed) next.withKeyframeToggled(ref, "0") else next }
                                    }
                                }
                                GradeChip(stringResource(R.string.true_grade_neutral), false) {
                                    state.endGesture(); change("RGB".associate { "$tone$it" to 0f }, false)
                                }
                            }
                        }
                    }
                    2 -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Y", "R", "G", "B").forEach { id ->
                                GradeChip(if (id == "Y") stringResource(R.string.true_grade_master) else id, channel == id, Modifier.weight(1f), channelColor(id)) {
                                    state.endGesture(); channel = id; activate("curve${id}2")
                                }
                            }
                        }
                        val keys = ColorGrade.curveKeys(channel)
                        GradeCurve(keys.map(::value), active.removePrefix("curve$channel").toIntOrNull() ?: 2,
                            channelColor(channel), stringResource(R.string.true_grade_curve_control), Modifier.fillMaxWidth().height(132.dp), { point, y ->
                                activate(keys[point]); change(mapOf(keys[point] to y))
                            }, state::endGesture)
                        Text(stringResource(R.string.true_grade_curve_hint), color = Am.OnDarkDim, fontSize = 11.sp)
                    }
                    3 -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ColorGrade.bands.forEachIndexed { i, id ->
                                val name = stringResource(bandLabel(id))
                                val color = listOf(Color(0xffed596a), Color(0xffffd361), Color(0xff7bdb96), Color(0xff69dce5), Color(0xff7f9df9), Color(0xffe48add))[i]
                                Box(Modifier.size(40.dp).clip(CircleShape).background(if (band == id) Am.Chosen else Am.Field)
                                    .border(if (band == id) 2.dp else 0.dp, if (band == id) Am.Accent else Color.Transparent, CircleShape)
                                    .clickable { state.endGesture(); band = id; activate("hsl${id}H") }.semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(22.dp).clip(CircleShape).background(color))
                                }
                            }
                        }
                        Text(stringResource(bandLabel(band)), color = Am.OnDark, fontSize = 13.sp)
                    }
                }
                section.forEach { id ->
                    val p = definition.params.first { it.id == id }
                    val label = when {
                        tab == 1 -> if (id == tone) stringResource(R.string.true_grade_brightness) else id.takeLast(1)
                        tab == 2 -> stringResource(R.string.true_grade_curve_point, id.takeLast(1).toInt() * 25)
                        tab == 3 -> stringResource(when (id.last()) { 'H' -> R.string.true_grade_hue; 'S' -> R.string.true_grade_saturation; else -> R.string.true_grade_lightness })
                        else -> stringResource(parameterLabel(id))
                    }
                    val ref = PropRef.effect(index, id)
                    GradeSlider(label, value(id), p.min..p.max, active == id, layer.keyframes(ref).isNotEmpty(), layer.onKeyframe(ref),
                        onNumber = { numeric(id, label) }, onKey = {
                            state.endGesture(); activate(id)
                            state.editSelected { it.withKeyframeToggled(ref, p.missingDefault) }
                        }, onChange = { activate(id); change(mapOf(id to it)) }, onFinish = state::endGesture)
                }
                if (tab == 4) Text(stringResource(R.string.true_grade_mix_hint), color = Am.OnDarkDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun GradeIcon(icon: ImageVector, label: String, lit: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(21.dp), tint = if (lit) Am.OnDark else Am.OnDarkDim)
    }
}

@Composable
private fun GradeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, ink: Color = Am.Accent, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Am.Chosen else Am.Field)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) ink else Am.OnDarkDim, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun GradeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, active: Boolean, animated: Boolean, keyed: Boolean,
    onNumber: () -> Unit, onKey: () -> Unit, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val numberLabel = stringResource(R.string.true_grade_enter_value, label)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Am.Field).padding(start = 10.dp, end = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = if (active) Am.Accent else Am.OnDark, fontSize = 12.sp)
            Text(String.format(Locale.US, "%.3f", value), Modifier.clip(RoundedCornerShape(5.dp)).background(Am.Button).clickable(onClick = onNumber).padding(horizontal = 10.dp, vertical = 6.dp)
                .semantics { contentDescription = numberLabel }, color = Am.OnDark, fontSize = 12.sp)
            val keyLabel = stringResource(if (keyed) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe) + ": " + label
            Box(Modifier.size(36.dp).clickable(onClick = onKey), contentAlignment = Alignment.Center) {
                Icon(if (keyed) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, keyLabel, Modifier.size(19.dp), tint = if (animated) Am.Accent else Am.OnDarkDim)
            }
        }
        Slider(value.coerceIn(range), onChange, Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = label }, onValueChangeFinished = onFinish,
            valueRange = range, colors = SliderDefaults.colors(thumbColor = Am.Accent, activeTrackColor = Am.Accent, inactiveTrackColor = Am.Line))
    }
}

/** Consumes touches only inside the graph. Pointer callbacks stay current without restarting a drag. */
private fun Modifier.gradeGesture(onPosition: (Offset, androidx.compose.ui.unit.IntSize) -> Unit, onFinish: () -> Unit) = pointerInput(Unit) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume(); onPosition(down.position, size)
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (change.pressed) onPosition(change.position, size)
            } while (change.pressed)
            onFinish()
        }
    } finally { onFinish() }
}

@Composable
private fun GradeWheel(rgb: FloatArray, description: String, modifier: Modifier, onChange: (FloatArray) -> Unit, onFinish: () -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val latestFinish by rememberUpdatedState(onFinish)
    val latestRgb by rememberUpdatedState(rgb)
    val insetPx = with(LocalDensity.current) { 12.dp.toPx() }
    val position = ColorGrade.wheel(rgb)
    val outline = Am.OnDark
    Canvas(modifier.semantics { contentDescription = description; stateDescription = "${position.hue.roundToInt()}°, ${(position.amount * 100).roundToInt()}%" }
        .gradeGesture({ p, size ->
            val radius = min(size.width, size.height) / 2f - insetPx
            val dx = p.x - size.width / 2f; val dy = p.y - size.height / 2f
            val hue = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
            latestChange(ColorGrade.rgb(hue, (hypot(dx, dy) / radius).coerceIn(0f, 1f), latestRgb.average().toFloat()))
        }, { latestFinish() })) {
        val r = size.minDimension / 2f - 12.dp.toPx()
        drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red), center), r)
        drawCircle(Brush.radialGradient(listOf(Color(0xff92979f), Color(0x0092979f)), center, r), r)
        drawCircle(outline.copy(alpha = .4f), r, style = Stroke(1.dp.toPx()))
        drawLine(outline.copy(alpha = .5f), center - Offset(5.dp.toPx(), 0f), center + Offset(5.dp.toPx(), 0f), 1.dp.toPx())
        drawLine(outline.copy(alpha = .5f), center - Offset(0f, 5.dp.toPx()), center + Offset(0f, 5.dp.toPx()), 1.dp.toPx())
        val angle = Math.toRadians(-position.hue.toDouble())
        val marker = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r * position.amount)
        drawCircle(Color.Black.copy(alpha = .7f), 8.dp.toPx(), marker, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun GradeCurve(values: List<Float>, active: Int, color: Color, description: String, modifier: Modifier, onChange: (Int, Float) -> Unit, onFinish: () -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val latestFinish by rememberUpdatedState(onFinish)
    var dragging by remember { mutableIntStateOf(-1) }
    val insetPx = with(LocalDensity.current) { 14.dp.toPx() }
    val grid = Am.Line; val bg = Am.Field; val outline = Am.OnDark
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(bg).semantics { contentDescription = description }
        .gradeGesture({ p, size ->
            val inset = insetPx
            if (dragging < 0) dragging = (((p.x - inset) / (size.width - 2 * inset) * 4f).roundToInt() - 1).coerceIn(0, 2)
            latestChange(dragging, (1f - (p.y - inset) / (size.height - 2 * inset)).coerceIn(0f, 1f))
        }, { dragging = -1; latestFinish() })) {
        val inset = 14.dp.toPx()
        val w = size.width - 2 * inset; val h = size.height - 2 * inset
        fun point(x: Float, y: Float) = Offset(inset + x * w, inset + (1 - y) * h)
        for (n in 0..4) {
            drawLine(grid, point(n / 4f, 0f), point(n / 4f, 1f), 1.dp.toPx())
            drawLine(grid, point(0f, n / 4f), point(1f, n / 4f), 1.dp.toPx())
        }
        drawLine(outline.copy(alpha = .25f), point(0f, 0f), point(1f, 1f), 1.dp.toPx())
        val path = Path().apply { moveTo(inset, inset + h); (1..4).forEach { n -> val p = point(n / 4f, ColorGrade.curve(n / 4f, values)); lineTo(p.x, p.y) } }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        values.forEachIndexed { i, value ->
            val p = point((i + 1) / 4f, value)
            drawCircle(bg, 6.dp.toPx(), p)
            drawCircle(if (active == i + 1) Color.White else color, 6.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun channelColor(id: String) = when (id) { "R" -> Color(0xffed7482); "G" -> Color(0xff7bdb96); "B" -> Color(0xff86a6ff); else -> Color(0xffe5eafa) }
private fun toneLabel(id: String) = when (id) { "lift" -> R.string.true_grade_lift; "gamma" -> R.string.true_grade_gamma; else -> R.string.true_grade_gain }
private fun bandLabel(id: String) = when (id) {
    "Red" -> R.string.true_grade_red; "Yellow" -> R.string.true_grade_yellow; "Green" -> R.string.true_grade_green
    "Cyan" -> R.string.true_grade_cyan; "Blue" -> R.string.true_grade_blue; else -> R.string.true_grade_magenta
}
private fun parameterLabel(id: String) = when (id) {
    "exposure" -> R.string.true_grade_exposure; "contrast" -> R.string.true_grade_contrast; "saturation" -> R.string.true_grade_saturation
    "vibrance" -> R.string.true_grade_vibrance; "temperature" -> R.string.true_grade_temperature; "tint" -> R.string.true_grade_tint
    "shadows" -> R.string.true_grade_shadows; "highlights" -> R.string.true_grade_highlights; "blackPoint" -> R.string.true_grade_black
    "whitePoint" -> R.string.true_grade_white; else -> R.string.true_grade_mix
}
