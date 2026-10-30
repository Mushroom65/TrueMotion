package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.Layer
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.channels.Channel
import java.util.Locale

private data class AnchorRequest(val x: Float, val y: Float, val finish: Boolean = false, val drag: Boolean = false)

@Composable
internal fun AnchorPanel(state: EditorState, layer: Layer, modifier: Modifier) {
    var keep by rememberSaveable(layer.id) { mutableStateOf(true) }
    var failed by remember(layer.id) { mutableStateOf(false) }
    val keepNow by rememberUpdatedState(keep)
    val current by rememberUpdatedState(layer)
    val requests = remember(layer.id) { Channel<AnchorRequest>(Channel.CONFLATED) }
    val box by produceState<FloatArray?>(null, layer.id, state.scene, state.timeMs, state.playing) {
        if (!state.playing) value = state.engine.localBounds(layer.id, state.timeMs)
    }
    suspend fun move(request: AnchorRequest) {
        if (!request.x.isFinite() || !request.y.isFinite()) return
        state.playing = false
        val before = state.scene
        val time = state.timeMs
        val raw = before.layer(layer.id)?.at(time, before.fps) ?: return
        val location = if (keepNow) state.engine.reanchor(layer.id, time, request.x, request.y) else raw.location
        if (state.scene != before || state.timeMs != time || state.selectedId != layer.id) return
        if (location == null) {
            failed = true
            if (request.finish || !request.drag) state.endGesture()
            return
        }
        failed = false
        state.edit(if (request.drag) "anchor-${layer.id}" else null) { scene ->
            scene.update(layer.id, time) {
                val anchored = it.withTransform("pivot", request.x, request.y)
                if (keepNow) anchored.withTransform("location", *location) else anchored
            }
        }
        if (request.finish || !request.drag) state.endGesture()
    }
    LaunchedEffect(layer.id) { for (request in requests) move(request) }
    DisposableEffect(layer.id) { onDispose { requests.close(); state.endGesture() } }
    fun numeric(active: Int) {
        state.playing = false; state.endGesture()
        val pivot = current.pivot
        state.keypad = KeypadRequest(listOf(KeypadField("X", pivot[0]), KeypadField("Y", pivot[1])), active) { values ->
            requests.trySend(AnchorRequest(values[0], values[1]))
        }
    }
    val caption = stringResource(R.string.true_pivot_title)
    Column(modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(caption, Modifier.weight(1f), color = Am.OnDark, fontSize = 12.sp)
            listOf("X", "Y").forEachIndexed { index, axis ->
                Text("$axis ${String.format(Locale.US, "%.2f", layer.pivot[index])}",
                    Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(Am.Field).clickable { numeric(index) }
                        .semantics { contentDescription = "$caption $axis" }.padding(horizontal = 9.dp, vertical = 7.dp), color = Am.Accent, fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(32.dp).clickable { keep = !keep }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(keep, null, Modifier.size(26.dp), colors = CheckboxDefaults.colors(checkedColor = Am.Accent, checkmarkColor = Am.OnAccent, uncheckedColor = Am.OnDarkDim))
            Text(stringResource(R.string.true_pivot_keep), Modifier.padding(start = 7.dp), color = Am.OnDarkDim, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Am.Field)
                .pointerInput(layer.id) {
                    var origin = Offset.Zero; var travel = Offset.Zero
                    detectDragGestures(onDragStart = { origin = Offset(current.pivot[0], current.pivot[1]); travel = Offset.Zero },
                        onDragEnd = { val final = origin + travel; requests.trySend(AnchorRequest(final.x, final.y, finish = true, drag = true)) },
                        onDragCancel = { val final = origin + travel; requests.trySend(AnchorRequest(final.x, final.y, finish = true, drag = true)) }) { change, delta ->
                        change.consume(); travel += delta / density
                        val next = origin + travel; requests.trySend(AnchorRequest(next.x, next.y, drag = true))
                    }
                }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(PanelIcons.TabPivot, null, Modifier.size(28.dp), tint = Am.Accent)
                    Text(stringResource(R.string.true_pivot_drag), Modifier.padding(5.dp), color = Am.OnDarkDim, fontSize = 11.sp)
                }
            }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (col in 0..2) Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Am.Field)
                        .clickable(box != null) {
                            val bounds = box ?: return@clickable
                            requests.trySend(AnchorRequest(bounds[0] + (bounds[2] - bounds[0]) * col / 2, bounds[1] + (bounds[3] - bounds[1]) * row / 2))
                        }.semantics { contentDescription = "$caption ${row + 1}, ${col + 1}" }, contentAlignment = Alignment.Center) {
                        Icon(PanelIcons.TabPivot, null, Modifier.size(13.dp), tint = Am.OnDarkDim)
                    }
                }
                Box(Modifier.height(24.dp).clickable { requests.trySend(AnchorRequest(0f, 0f)) }, contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.true_pivot_reset), color = Am.Accent, fontSize = 10.sp)
                }
            }
        }
        if (failed) Text(stringResource(R.string.true_pivot_cannot_keep), color = Am.Warning, fontSize = 10.sp)
    }
}
