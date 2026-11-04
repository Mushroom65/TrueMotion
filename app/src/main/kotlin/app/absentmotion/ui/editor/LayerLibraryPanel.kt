package app.absentmotion.ui.editor

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.media.*
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am

@Composable
internal fun LayerLibraryPanel(state: EditorState, modifier: Modifier) {
    val layer = state.selected ?: return
    val context = app.absentmotion.platform.LocalContext.current
    val busy = state.addingMedia
    val active = state.scene.activeLayerAsset(layer, state.timeMs)
    val onKey = state.scene.onAssetKey(layer, state.timeMs)
    val canChoose = !layer.isMediaSwitch || onKey
    val inRange = state.timeMs in layer.startTime..layer.endTime
    SideEffect { state.keyTarget = KeyTarget(MediaSwitchRef, active?.uri.orEmpty(), "string") }
    DisposableEffect(Unit) { onDispose { state.keyTarget = null } }
    Row(modifier.background(Am.Panel)) {
        Column(Modifier.width(48.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            BarButton(ChromeIcons.Back, stringResource(R.string.topbar_back), Modifier.fillMaxWidth().height(42.dp)) { state.stepBack() }
            BarButton(if (onKey) PanelIcons.KeyframeRemove else PanelIcons.Keyframe,
                stringResource(if (onKey) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), Modifier.fillMaxWidth().height(42.dp), enabled = active != null && inRange && !busy) {
                state.edit { it.toggleAssetKey(layer.id, state.timeMs) }
            }
            BarButton(ChromeIcons.SkipBack, stringResource(R.string.library_previous_key), Modifier.fillMaxWidth().height(38.dp)) {
                state.frame = layer.mediaSwitchKeys.map { state.scene.frameAt(layer.timeOf(it.t)) }.lastOrNull { it < state.frame } ?: state.frame
            }
            BarButton(ChromeIcons.SkipForward, stringResource(R.string.library_next_key), Modifier.fillMaxWidth().height(38.dp)) {
                state.frame = layer.mediaSwitchKeys.map { state.scene.frameAt(layer.timeOf(it.t)) }.firstOrNull { it > state.frame } ?: state.frame
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(stringResource(if (canChoose) R.string.library_choose_hint else R.string.library_create_key_hint),
                Modifier.fillMaxWidth().padding(6.dp), color = if (canChoose) Am.OnDarkDim else Am.Accent, fontSize = 11.sp)
            LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.weight(1f).fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "add") {
                    val label = stringResource(R.string.asset_import)
                    Box(Modifier.aspectRatio(1f).background(Am.Well).semantics { contentDescription = label }
                        .clickable(enabled = !busy) { MediaPicks.openVisual(layer.id) }, Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Am.Accent)
                        else Icon(ChromeIcons.Plus, label, Modifier.size(30.dp), tint = Am.Accent)
                    }
                }
                items(state.scene.layerAssets(layer), key = { it.uri }) { media ->
                    val uri = remember(media.uri) { Uri.parse(media.uri) }
                    val bitmap by produceState(cachedMediaThumbnail(uri), uri) { value = mediaThumbnail(context, uri) }
                    val selected = active?.uri == media.uri
                    Box(Modifier.aspectRatio(1f).background(Am.Well)
                        .border(if (selected) 2.dp else 0.dp, if (selected) Am.Accent else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { contentDescription = media.title }
                        .clickable(enabled = !busy && canChoose && (!layer.isMediaSwitch || inRange)) { state.edit { it.chooseLayerAsset(layer.id, state.timeMs, media) } }) {
                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Icon(ChromeIcons.MediaTab, null, Modifier.align(Alignment.Center).size(26.dp), tint = Am.OnDarkDim)
                        if (media.type.startsWith("video/")) MediaPlayIcon(Modifier.align(Alignment.BottomStart).padding(5.dp).size(13.dp))
                        if (selected) Icon(PanelIcons.Check, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp), tint = Am.Accent)
                    }
                }
            }
        }
    }
}
