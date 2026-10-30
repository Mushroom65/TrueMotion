package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.editor.panels.formatFixed
import app.absentmotion.ui.theme.Am

@Composable
internal fun MotionPathPanel(state: EditorState, modifier: Modifier) {
    val layer = state.selected ?: return
    DisposableEffect(layer.id) { onDispose { state.endGesture() } }
    val keys = layer.keyframes(PropRef.Location)
    var chosen by remember(layer.id) { mutableIntStateOf(0) }
    val index = chosen.coerceIn(0, keys.lastIndex.coerceAtLeast(0))
    var part by remember(layer.id) { mutableIntStateOf(0) } // 0 point, 1 incoming, 2 outgoing
    var linked by remember { mutableStateOf(true) }
    var fit by remember(layer.id) { mutableIntStateOf(0) }
    fun edit(change: (Layer) -> Layer) { state.editSelected(change = change) }
    fun numeric() {
        val k = keys.getOrNull(index) ?: return
        val v = if (part == 0) k.pathPoint() else k.pathHandle(part == 1)
        state.keypad = KeypadRequest(listOf(KeypadField("X", v.x), KeypadField("Y", v.y)), 0) { values ->
            if (state.selectedId == layer.id) edit { current ->
                if (part == 0) current.withPathPoint(index, PathVector(values[0], values[1]))
                else current.withPathHandle(index, part == 1, PathVector(values[0], values[1]), linked)
            }
        }
    }
    Column(modifier.background(Am.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(ChromeIcons.Back, stringResource(R.string.topbar_back), Modifier.size(34.dp)) { state.endGesture(); state.motionPathOpen = false }
            Text(stringResource(R.string.true_path_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp)
            TextButton({ fit++ }) { Text(stringResource(R.string.true_path_fit), color = Am.Accent, fontSize = 11.sp) }
        }
        if (keys.size < 2) {
            Text(stringResource(R.string.true_path_need_keys), color = Am.OnDarkDim, fontSize = 12.sp)
            Button({ edit { it.withPathEndpoints(state.scene.width, state.scene.height) } }) { Text(stringResource(R.string.true_path_create)) }
        } else {
            Row(Modifier.fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ edit { it.withSmoothedPath() }; fit++ }) { Text(stringResource(R.string.true_path_smooth), color = Am.Accent, fontSize = 11.sp) }
                TextButton({ edit { it.withSmoothedPath(index) } }) { Text(stringResource(R.string.true_path_smooth_key), color = Am.OnDark, fontSize = 11.sp) }
                TextButton({ edit { it.withSmoothedPath(index, true) } }) { Text(stringResource(R.string.true_path_corner), color = Am.OnDark, fontSize = 11.sp) }
            }
            PathMap(state, layer, index, part, linked, fit, { k, p ->
                chosen = k; part = p
                state.frame = state.scene.frameAt(layer.timeOf(keys[k].t)).coerceAtLeast(0)
            }, Modifier.fillMaxWidth().weight(1f).heightIn(min = 75.dp))
            Row(Modifier.fillMaxWidth().height(30.dp).horizontalScroll(rememberScrollState())) {
                keys.forEachIndexed { i, k -> TextButton({ chosen = i; part = 0; state.frame = state.scene.frameAt(layer.timeOf(k.t)).coerceAtLeast(0) }) {
                    Text("${i + 1}", color = if (i == index) Am.Accent else Am.OnDarkDim, fontSize = 11.sp)
                } }
            }
            Row(Modifier.fillMaxWidth().height(30.dp).horizontalScroll(rememberScrollState())) {
                listOf(R.string.true_path_point, R.string.true_path_in, R.string.true_path_out).forEachIndexed { i, name ->
                    val available = i == 0 || (i == 1 && index > 0) || (i == 2 && index < keys.lastIndex)
                    TextButton({ part = i }, enabled = available) { Text(stringResource(name), color = if (!available) Am.OnDarkDim.copy(alpha = .4f) else if (part == i) Am.Accent else Am.OnDarkDim, fontSize = 11.sp) }
                }
                val k = keys[index]
                val v = if (part == 0) k.pathPoint() else k.pathHandle(part == 1)
                TextButton(::numeric) { Text("X ${formatFixed(v.x, 2)}   Y ${formatFixed(v.y, 2)}", color = Am.OnDark, fontSize = 11.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            PathToggle(R.string.true_path_enabled, layer.pathEnabled) { edit { layer -> layer.withMotionPath(it) } }
            PathToggle(R.string.true_path_link, linked) { linked = it }
            PathToggle(R.string.true_path_orient, layer.pathAutoOrient) { edit { layer -> layer.withPathAutoOrient(it) } }
        }
        Text(stringResource(R.string.true_path_hint), color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun PathToggle(label: Int, value: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, change, Modifier.size(32.dp), colors = CheckboxDefaults.colors(checkedColor = Am.Accent, uncheckedColor = Am.OnDarkDim, checkmarkColor = Am.Panel))
        Text(stringResource(label), color = Am.OnDark, fontSize = 10.sp)
    }
}

private data class PathMapView(val scale: Float, val origin: Offset) {
    fun map(p: PathVector) = Offset(p.x * scale, p.y * scale) + origin
}

@Composable
private fun PathMap(state: EditorState, layer: Layer, selected: Int, part: Int, linked: Boolean, fit: Int,
                    choose: (Int, Int) -> Unit, modifier: Modifier) {
    val keys = layer.keyframes(PropRef.Location)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(layer.id, fit) { mutableFloatStateOf(1f) }
    var pan by remember(layer.id, fit) { mutableStateOf(Offset.Zero) }
    val bounds = remember(layer.id, fit) {
        val points = keys.flatMapIndexed { i, k -> listOfNotNull(k.pathPoint(),
            if (i > 0) k.pathPoint() + k.pathHandle(true) else null,
            if (i < keys.lastIndex) k.pathPoint() + k.pathHandle(false) else null) }
        val left = points.minOf { it.x }; val right = points.maxOf { it.x }
        val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        val rx = ((right - left) / 2).coerceAtLeast(16f); val ry = ((bottom - top) / 2).coerceAtLeast(16f)
        floatArrayOf(cx - rx, cy - ry, cx + rx, cy + ry)
    }
    val fitScale = minOf((size.width - 48f).coerceAtLeast(1f) / (bounds[2] - bounds[0]).coerceAtLeast(1f),
        (size.height - 48f).coerceAtLeast(1f) / (bounds[3] - bounds[1]).coerceAtLeast(1f))
    val scale = fitScale * zoom
    val view = PathMapView(scale, Offset(size.width / 2f - (bounds[0] + bounds[2]) * scale / 2,
        size.height / 2f - (bounds[1] + bounds[3]) * scale / 2) + pan)
    val now by rememberUpdatedState(layer)
    val map by rememberUpdatedState(view)
    val currentKeys by rememberUpdatedState(keys)
    val currentSelected by rememberUpdatedState(selected)
    val currentLinked by rememberUpdatedState(linked)
    val select by rememberUpdatedState(choose)
    Canvas(modifier.clipToBounds().background(Am.PreviewBg).onSizeChanged { size = it }.pointerInput(state, layer.id, fit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val candidates = currentKeys.mapIndexed { i, k -> Triple(i, 0, map.map(k.pathPoint())) }.toMutableList()
            val k = currentKeys.getOrNull(currentSelected)
            if (k != null) for (p in 1..2) {
                if ((p == 1 && currentSelected > 0) || (p == 2 && currentSelected < currentKeys.lastIndex))
                    candidates.add(Triple(currentSelected, p, map.map(k.pathPoint() + k.pathHandle(p == 1))))
            }
            // A point wins when collapsed handles share its position.
            var target = candidates.minByOrNull { (_, _, at) -> (at - down.position).getDistance() }
                ?.takeIf { (_, _, at) -> (at - down.position).getDistance() <= 22.dp.toPx() }
            if (target != null) select(target.first, target.second)
            var moved = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    target = null
                    val factor = event.calculateZoom()
                    val about = event.calculateCentroid(useCurrent = false)
                    val oldZoom = zoom
                    zoom = (zoom * factor).coerceIn(.1f, 20f)
                    val actual = zoom / oldZoom
                    pan = (pan - (about - Offset(size.width / 2f, size.height / 2f))) * actual + (about - Offset(size.width / 2f, size.height / 2f)) + event.calculatePan()
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1 && pressed[0].positionChanged()) {
                    val change = pressed[0]
                    val delta = change.position - change.previousPosition
                    if (target == null) pan += delta
                    else {
                        val (i, p) = target
                        val key = now.keyframes(PropRef.Location).getOrNull(i)
                        if (key != null) {
                            val shift = PathVector(delta.x / map.scale, delta.y / map.scale)
                            state.editSelected(key = "motion-path-drag") { current ->
                                if (p == 0) current.withPathPoint(i, key.pathPoint() + shift)
                                else current.withPathHandle(i, p == 1, key.pathHandle(p == 1) + shift, currentLinked)
                            }
                            moved = true
                        }
                    }
                    change.consume()
                }
            } while (event.changes.any { it.pressed })
            if (moved) state.endGesture()
        }
    }) {
        val thin = 1.dp.toPx()
        drawRect(Am.OnDarkDim.copy(alpha = .15f), view.map(PathVector()), androidx.compose.ui.geometry.Size(state.scene.width * scale, state.scene.height * scale), style = Stroke(thin))
        for ((a, b) in keys.zipWithNext()) {
            val p = view.map(a.pathPoint()); val q = view.map(b.pathPoint())
            val out = a.pathHandle(false); val inside = b.pathHandle(true)
            val curve = Path().apply {
                moveTo(p.x, p.y)
                if (!layer.pathEnabled || (out == PathVector() && inside == PathVector())) lineTo(q.x, q.y)
                else {
                    val c = view.map(a.pathPoint() + out); val d = view.map(b.pathPoint() + inside)
                    cubicTo(c.x, c.y, d.x, d.y, q.x, q.y)
                }
            }
            drawPath(curve, Am.Accent, style = Stroke(2.dp.toPx()))
        }
        keys.getOrNull(selected)?.let { key ->
            val point = view.map(key.pathPoint())
            for (p in 1..2) {
                if ((p == 1 && selected == 0) || (p == 2 && selected == keys.lastIndex)) continue
                val at = view.map(key.pathPoint() + key.pathHandle(p == 1))
                drawLine(Am.OnDarkDim, point, at, thin)
                drawCircle(if (part == p) Am.Accent else Am.OnDarkDim, 5.dp.toPx(), at)
            }
        }
        keys.forEachIndexed { i, k ->
            val p = view.map(k.pathPoint())
            drawCircle(Am.Panel, 6.dp.toPx(), p)
            drawCircle(if (i == selected) Am.Accent else Am.OnDark, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        val position = layer.at(state.timeMs, state.scene.fps).location
        drawCircle(Am.Warning, 3.dp.toPx(), view.map(PathVector(position[0], position[1])))
    }
}
