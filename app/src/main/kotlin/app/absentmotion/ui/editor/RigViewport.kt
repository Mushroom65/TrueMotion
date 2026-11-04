package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private data class BoneLine(val bone: Layer, val from: Offset, val to: Offset)

private fun lines(state: EditorState): List<BoneLine> = state.scene.bones.map { bone ->
    val place = state.scene.place(bone, state.sampleMs)
    val angle = Math.toRadians(place.rot.toDouble())
    BoneLine(bone, Offset(place.x, place.y), Offset(place.x + (cos(angle) * bone.rigLength * place.sx).toFloat(), place.y + (sin(angle) * bone.rigLength * place.sx).toFloat()))
}

@Composable
internal fun RigViewport(state: EditorState, scale: Float, offset: Offset, quad: FloatArray?, modifier: Modifier) {
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)
    val scope = rememberCoroutineScope()
    var ghost by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val accent = Am.Accent
    val normal = Am.Gold
    DisposableEffect(Unit) { onDispose { state.endGesture() } }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown()
                state.playing = false
                val initialScale = currentScale.coerceAtLeast(0.00001f)
                val initialOffset = currentOffset
                fun world(point: Offset) = (point - initialOffset) / initialScale
                val start = world(down.position)
                val candidates = lines(state)
                val reach = 22.dp.toPx() / initialScale
                val tip = candidates.minByOrNull { (it.to - start).getDistance() }?.takeIf { (it.to - start).getDistance() < reach }
                val root = candidates.filter { it.bone.node["parent"] == null }.minByOrNull { (it.from - start).getDistance() }
                    ?.takeIf { (it.from - start).getDistance() < reach }
                fun distance(line: BoneLine): Float {
                    val delta = line.to - line.from
                    val length2 = delta.x * delta.x + delta.y * delta.y
                    val u = if (length2 < .00001f) 0f else (((start - line.from).x * delta.x + (start - line.from).y * delta.y) / length2).coerceIn(0f, 1f)
                    return (start - line.from - delta * u).getDistance()
                }
                val body = candidates.minByOrNull(::distance)?.takeIf { distance(it) < reach * .65f }
                val grabbed = tip ?: root ?: body
                val rootHandle = tip == null && root != null
                val from = if (state.rigTool == 0) tip?.to ?: start else start
                val tool = state.rigTool
                var dragged = false
                var multi = false
                var end = start
                if (grabbed != null) state.rigBoneId = grabbed.bone.id
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (event.changes.size > 1) {
                        multi = true; ghost = null
                        val zoom = event.calculateZoom()
                        val next = (state.canvasZoom * zoom).coerceIn(0.01f, 64f)
                        val centre = event.calculateCentroid(useCurrent = false)
                        val about = if (centre.isSpecified) (centre - currentOffset) / currentScale - Offset(state.scene.width / 2f, state.scene.height / 2f) else Offset.Zero
                        state.canvasPan = pinchedPan(state.canvasPan + event.calculatePan() / currentScale, about, next / state.canvasZoom)
                        state.canvasZoom = next
                    } else if (!multi) {
                        end = world(change.position)
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (dragged) {
                            if (tool == 0 && state.selected != null) ghost = from to end
                            else if (grabbed != null) state.poseViewportBone(grabbed.bone.id, Pt(end.x, end.y), rootHandle, tip != null)
                            else state.canvasPan += event.calculatePan() / currentScale
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                ghost = null
                if (!multi && dragged && tool == 0) state.drawRigBone(tip?.bone?.id, Pt(from.x, from.y), Pt(end.x, end.y))
                else if (!multi && !dragged) {
                    if (grabbed != null) {
                        if (tool == 0) state.bindViewportBone(grabbed.bone.id)
                    } else scope.launch {
                        val hit = state.engine.hitTest(start.x, start.y, state.timeMs)
                        state.scene.layer(hit)?.takeUnless { it.isBone || it.kind in setOf("audio", "camera", "nullobj") }?.let { art ->
                            state.selectedId = art.id
                            state.rigBoneId = art.node["parent"]?.toLongOrNull()?.takeIf { state.scene.layer(it)?.isBone == true }
                        }
                    }
                }
                state.endGesture()
            }
        }) {
            fun screen(point: Offset) = point * scale + offset
            fun drawBone(a: Offset, b: Offset, color: Color) {
                val direction = b - a
                val length = direction.getDistance().coerceAtLeast(1f)
                val normalAxis = Offset(-direction.y / length, direction.x / length) * minOf(9.dp.toPx(), length / 5)
                val shoulder = a + direction * .18f
                val shape = Path().apply { moveTo(a.x, a.y); lineTo((shoulder + normalAxis).x, (shoulder + normalAxis).y); lineTo(b.x, b.y); lineTo((shoulder - normalAxis).x, (shoulder - normalAxis).y); close() }
                drawPath(shape, color.copy(alpha = .38f))
                drawPath(shape, Color.Black.copy(alpha = .65f), style = Stroke(4.dp.toPx()))
                drawPath(shape, color, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black, 6.dp.toPx(), a)
                drawCircle(color, 4.dp.toPx(), a)
                drawCircle(Color.Black, 6.dp.toPx(), b)
                drawCircle(color, 4.dp.toPx(), b)
            }
            if (state.rigTool == 0 && quad != null) {
                val corners = List(4) { Offset(quad[it * 2], quad[it * 2 + 1]) * scale + offset }
                for (index in corners.indices) drawLine(accent.copy(alpha = .6f), corners[index], corners[(index + 1) % 4], 1.dp.toPx())
            }
            lines(state).forEach { line -> drawBone(screen(line.from), screen(line.to), if (state.rigBoneId == line.bone.id) accent else normal) }
            ghost?.let { drawBone(screen(it.first), screen(it.second), accent) }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Am.Panel.copy(alpha = .94f))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf(R.string.rig_draw, R.string.rig_pose, R.string.rig_ik_mode).forEachIndexed { index, title ->
                    TextButton({ state.endGesture(); state.rigTool = index }) { Text(stringResource(title), color = if (state.rigTool == index) Am.Accent else Am.OnDark, fontSize = 12.sp) }
                }
                val bone = state.rigBoneId?.let { state.scene.layerAt(it, state.sampleMs) }
                BarButton(if (bone?.onKeyframe(PropRef.Rotation) == true) PanelIcons.KeyframeRemove else PanelIcons.Keyframe,
                    stringResource(R.string.a11y_add_keyframe), Modifier.size(38.dp), enabled = bone != null) { state.toggleRigKey() }
                TextButton({ state.rigKeyed = !state.rigKeyed }) { Text(stringResource(R.string.rig_auto_keys), color = if (state.rigKeyed) Am.Accent else Am.OnDarkDim, fontSize = 11.sp) }
                if (state.rigTool == 2) TextButton({ state.rigFlip = !state.rigFlip }) { Text(stringResource(R.string.rig_flip), color = Am.OnDark, fontSize = 11.sp) }
                TextButton({ state.deleteViewportBone() }, enabled = bone != null) { Text(stringResource(R.string.asset_remove), color = Am.OnDarkDim, fontSize = 11.sp) }
                }
                BarButton(PanelIcons.Check, stringResource(R.string.done), Modifier.size(42.dp)) { state.endGesture(); state.rigEditing = false; state.keyTarget = null }
            }
            Text(stringResource(if (state.rigTool == 0) R.string.rig_draw_hint else R.string.rig_pose_hint), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Am.OnDarkDim, fontSize = 10.sp)
        }
        val bone = state.rigBoneId?.let { state.scene.layer(it) }
        if (bone != null) Row(Modifier.align(Alignment.BottomCenter).horizontalScroll(rememberScrollState()).background(Am.Panel.copy(alpha = .9f))) {
            bone.keyframes(PropRef.Rotation).forEach { key ->
                val frame = state.scene.frameAt(bone.timeOf(key.t))
                TextButton({ state.frame = frame }) { Text("◆ ${bone.timeOf(key.t)}", color = if (frame == state.frame) Am.Accent else Am.OnDarkDim, fontSize = 11.sp) }
            }
        }
    }
}
