package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.captureFramePhoto
import app.absentmotion.ui.editor.panels.askNumber
import app.absentmotion.ui.editor.panels.formatPlain
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun FramePhotoPanel(state: EditorState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seconds by remember { mutableFloatStateOf(state.settings.defaultLayerDuration.toFloat()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val source = state.selected?.takeIf { it.mediaFill?.video == true && it.activeAt(state.timeMs) }
    val busy = job?.isActive == true
    val durationLabel = stringResource(R.string.true_photo_duration_label)
    Column(modifier.background(Am.Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            BarButton(ChromeIcons.Back, stringResource(R.string.topbar_back), Modifier.size(36.dp)) { state.framePhotoOpen = false }
            Text(stringResource(R.string.true_photo_title), Modifier.padding(6.dp), color = Am.OnDark, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sourceMode in listOf(false, true)) TextButton(
                    { state.framePhotoSource = sourceMode; error = null }, Modifier.weight(1f), enabled = !busy && (!sourceMode || source != null),
                ) { Text(stringResource(if (sourceMode) R.string.true_photo_video else R.string.true_photo_composition), color = if (state.framePhotoSource == sourceMode) Am.Accent else Am.OnDarkDim, fontSize = 12.sp) }
            }
            Text(stringResource(if (state.framePhotoSource) R.string.true_photo_source_hint else R.string.true_photo_composition_hint), color = Am.OnDarkDim, fontSize = 11.sp)
            TextButton({ state.askNumber(durationLabel, seconds) { seconds = it.coerceIn(1f / state.scene.fps, 3600f) } }, enabled = !busy) {
                Text(stringResource(R.string.true_photo_duration, formatPlain(seconds)), color = Am.OnDark)
            }
            if (error != null) Text(error!!, color = Am.Warning, fontSize = 11.sp)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Am.Accent)
        Button({
            error = null
            job = scope.launch {
                try { state.captureFramePhoto(context, state.framePhotoSource, seconds) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: context.getString(R.string.true_photo_cannot_capture) }
                finally { job = null }
            }
        }, Modifier.fillMaxWidth().height(42.dp), enabled = !busy && (!state.framePhotoSource || source != null),
            colors = ButtonDefaults.buttonColors(containerColor = Am.Accent, contentColor = Am.Panel)) {
            Text(stringResource(if (busy) R.string.true_photo_working else R.string.true_photo_create))
        }
    }
}
