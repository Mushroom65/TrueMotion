package app.absentmotion.ui.editor.panels

import app.absentmotion.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.absentmotion.editor.EditorState
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiTools
import app.absentmotion.ui.editor.effects.AiProgressDialog
import app.absentmotion.ui.editor.effects.AiSmoothDialog
import androidx.compose.ui.platform.LocalContext
import app.absentmotion.editor.applySpeedRamp
import app.absentmotion.editor.deleteSpeedKeyframes
import app.absentmotion.editor.freezeFrame
import app.absentmotion.model.SpeedRamp
import app.absentmotion.editor.retimeVideo
import app.absentmotion.editor.setVideoSpeed
import app.absentmotion.model.Layer
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.PlaybackSpeed
import app.absentmotion.model.atVideoTime
import app.absentmotion.model.isKeyframed
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.rememberHaptic
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.absentmotion.ui.theme.Am
import kotlin.math.roundToInt

@Composable
fun SpeedPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var askRemove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember(state, context, scope) { AiTools(context.applicationContext, state, scope) }
    val speed = VideoTiming(layer).speed((state.sampleMs - layer.startTime).toDouble())
    val enabled = listOf(state.sampleMs < layer.startTime, state.sampleMs in (layer.startTime + 1) until layer.endTime,
        state.sampleMs in (layer.startTime + 1) until layer.endTime, state.sampleMs > layer.endTime)
    Box(modifier) {
        Row(Modifier.padding(horizontal = 15.dp).offset(y = 16.dp).fillMaxWidth().height(37.dp), Arrangement.spacedBy(1.5.dp)) {
            for (i in 0..3) {
                val shape = when (i) {
                    0 -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                    3 -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Box(Modifier.weight(1f).fillMaxHeight().alpha(if (enabled[i]) 1f else 0.35f)
                    .background(Am.Button, shape).clickable(enabled = enabled[i]) { state.retimeVideo(i < 2) }, Alignment.Center) {
                    RetimeGlyph(i, Modifier.size(23.dp))
                }
            }
        }
        fun applySpeed(value: Float) {
            val original = state.selected ?: return
            state.playing = false
            state.setVideoSpeed(value, original.atVideoTime(state.sampleMs, state.scene.fps).t, original)
            state.endGesture()
        }
        Row(Modifier.offset(y = 54.dp).fillMaxWidth().height(26.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp), Arrangement.spacedBy(4.dp)) {
            for (preset in PlaybackSpeed.presets) RampChip(formatPlain(preset) + "x", true) { applySpeed(preset) }
        }
        val speedLabel = stringResource(R.string.true_speed_number)
        Text(formatFixed(speed, 2) + "x", Modifier.align(Alignment.TopCenter).offset(y = 80.dp)
            .clickable { state.askNumber(speedLabel, speed, min = .001f, max = 100f, onDone = ::applySpeed) }.padding(horizontal = 12.dp, vertical = 4.dp), color = Am.OnDark, fontSize = 12.sp)
        Row(Modifier.offset(y = 105.dp).fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            PaceIcon(PanelIcons.Turtle, Modifier.width(44.dp).height(40.dp))
            SpeedSlider(speed, state, Modifier.weight(1f).fillMaxHeight())
            PaceIcon(PanelIcons.Rabbit, Modifier.width(44.dp).height(40.dp))
        }
        Row(Modifier.offset(y = 153.dp).fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp), Arrangement.spacedBy(6.dp)) {
            RampChip(stringResource(R.string.smooth_clip), layer.mediaFill?.video == true && ai.running == null) { state.playing = false; ai.apply(AiAction.Smooth) }
            for (ramp in SpeedRamp.entries) RampChip(stringResource(ramp.label), true) { state.applySpeedRamp(ramp) }
            val secondsLabel = stringResource(R.string.speed_seconds)
            RampChip(stringResource(R.string.speed_freeze_frame), enabled[1]) { state.askNumber(secondsLabel, 2f, "", 0.1f, 30f) { state.freezeFrame(it) } }
            RampChip(stringResource(R.string.speed_remove_keyframes), layer.node.child("speedMap")?.isKeyframed == true) { askRemove = true }
        }
    }
    AiSmoothDialog(ai, state.scene)
    AiProgressDialog(ai)
    if (askRemove) AlertDialog(onDismissRequest = { askRemove = false }, title = { Text(stringResource(R.string.remove_every_keyframe)) },
        confirmButton = { TextButton({ askRemove = false; state.deleteSpeedKeyframes() }) { Text(stringResource(R.string.delete), color = Am.Accent) } },
        dismissButton = { TextButton({ askRemove = false }) { Text(stringResource(R.string.cancel), color = Am.Accent) } }, containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark)
}

@Composable
private fun RampChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxHeight().alpha(if (enabled) 1f else 0.35f).background(Am.Button, RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), Alignment.Center) {
        Text(label, color = Am.OnDark, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SpeedSlider(speed: Float, state: EditorState, modifier: Modifier) {
    val liveLayer by rememberUpdatedState(state.selected)
    val haptic = rememberHaptic(state)
    fun coord(value: Float, width: Float, thumb: Float): Float = thumb / 2 + PlaybackSpeed.position(value) * (width - thumb).coerceAtLeast(1f)
    Canvas(modifier.sliderSemantics(speed, PlaybackSpeed.Min..PlaybackSpeed.Max) { v ->
        liveLayer?.let { state.setVideoSpeed(v, it.atVideoTime(state.sampleMs, state.scene.fps).t, it); state.endGesture() }
    }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val original = liveLayer ?: return@awaitEachGesture
            state.playing = false
            val anchor = original.atVideoTime(state.sampleMs, state.scene.fps).t
            val thumb = 24.dp.toPx()
            var detent: Float? = null
            var moved = false
            fun update(x: Float) {
                val u = ((x - thumb / 2) / (size.width - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val value = PlaybackSpeed.presets.minByOrNull { kotlin.math.abs(PlaybackSpeed.position(it) - u) }
                    ?.takeIf { kotlin.math.abs(PlaybackSpeed.position(it) - u) < .012f }
                if (moved && value != null && value != detent) haptic(HapticFeedbackType.SegmentTick)
                moved = true
                detent = value
                val raw = PlaybackSpeed.value(u)
                val precision = if (raw < 1f) 1000f else if (raw < 10f) 100f else 10f
                state.setVideoSpeed(value ?: ((raw * precision).roundToInt() / precision).coerceIn(PlaybackSpeed.Min, PlaybackSpeed.Max), anchor, original)
            }
            down.consume(); update(down.position.x)
            try {
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) { change.consume(); update(change.position.x) }
                } while (event.changes.any { it.pressed })
            } finally { state.endGesture() }
        }
    }) {
        val thumb = 24.dp.toPx(); val cy = size.height / 2
        val grey = Am.Caption
        drawLine(grey, Offset(thumb / 2, cy), Offset(size.width - thumb / 2, cy), 2.5.dp.toPx(), StrokeCap.Round)
        for (v in listOf(.001f, .1f, 1f, 4f, 10f, 100f)) {
            val x = coord(v, size.width, thumb)
            drawLine(grey, Offset(x, cy - 15.dp.toPx()), Offset(x, cy - 10.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
            drawLine(grey, Offset(x, cy + 10.dp.toPx()), Offset(x, cy + 15.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(Am.OnDark, thumb / 2, Offset(coord(speed, size.width, thumb), cy))
    }
}

@Composable
private fun RetimeGlyph(index: Int, modifier: Modifier) {
    Canvas(modifier) {
        val k = size.width / 24
        val start = index < 2
        val extend = index == 0 || index == 3
        fun p(x: Float, y: Float) = Offset((if (start) x else 24 - x) * k, y * k)
        val edge = if (extend) 1f else 12f
        drawLine(Am.OnDark, p(edge, 1f), p(edge, 23f), k, StrokeCap.Round)
        val left = if (extend) 4f else 1f
        val right = if (extend) 23f else 10f
        val box = Path().apply {
            moveTo(p(left, 6f).x, p(left, 6f).y); lineTo(p(right, 6f).x, p(right, 6f).y)
            lineTo(p(right, 18f).x, p(right, 18f).y); lineTo(p(left, 18f).x, p(left, 18f).y); close()
        }
        drawPath(box, Am.OnDark, style = Stroke(k))
        val dx = if (extend) 19f else 18f
        val diamond = Path().apply {
            val points = listOf(p(dx, 9f), p(dx + 1.7f, 12f), p(dx, 15f), p(dx - 1.7f, 12f))
            moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        drawPath(diamond, Am.OnDark)
        val ax = if (extend) 8f else 6f
        drawLine(Am.OnDark, p(ax - 3f, 12f), p(ax + 2f, 12f), k)
        val tip = if (extend) ax - 3 else ax + 2
        drawLine(Am.OnDark, p(tip, 12f), p(tip + if (extend) 2 else -2, 10f), k)
        drawLine(Am.OnDark, p(tip, 12f), p(tip + if (extend) 2 else -2, 14f), k)
    }
}

@Composable
private fun PaceIcon(icon: ImageVector, modifier: Modifier) {
    Box(modifier, Alignment.Center) { PanelIcon(icon, Am.Caption, size = 22.dp) }
}
