package app.absentmotion.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.editor.*
import app.absentmotion.library.rigModelStore
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.media.mediaThumbnail
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun AssetWorkspacePage(state: EditorState, modifier: Modifier, models: Boolean) {
    var open by remember { mutableStateOf(true) }
    Column(modifier.padding(12.dp)) {
        Text(stringResource(if (models) R.string.rig_help else R.string.asset_help), color = Am.OnDarkDim)
        TextButton({ open = true }) { Text(stringResource(if (models) R.string.rig_tab else R.string.asset_tab), color = Am.Accent) }
    }
    if (open) AssetWorkspace(state, models) { open = false }
}

@Composable
private fun AssetWorkspace(state: EditorState, models: Boolean, close: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.94f), color = Am.Panel, contentColor = Am.OnDark) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(if (models) R.string.rig_tab else R.string.asset_tab), style = MaterialTheme.typography.titleLarge)
                    TextButton(close, enabled = !busy) { Text(stringResource(R.string.true_close), color = Am.Accent) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(stringResource(R.string.true_operation_error, it), color = Am.Warning) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (models) RigWorkspace(state, busy, ::work, close) else LibraryWorkspace(state, busy, ::work, close)
                }
            }
        }
    }
}

@Composable
private fun LibraryWorkspace(state: EditorState, busy: Boolean, work: (suspend () -> Unit) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work {
            for (uri in uris) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val type = context.contentResolver.getType(uri).orEmpty()
                val record = if (type.startsWith("audio/")) audioDeclaration(context, uri) else mediaDeclaration(context, uri)
                requireNotNull(record) { context.getString(R.string.library_err_unsupported_type) }
                val kept = state.keepCopy(context, record)
                state.edit { it.withLibraryMedia(kept) }
            }
        }
    }
    Text(stringResource(R.string.asset_help), color = Am.OnDarkDim)
    TextButton({ picker.launch(arrayOf("image/*", "video/*", "audio/*")) }, enabled = !busy) { Text(stringResource(R.string.asset_import), color = Am.Accent) }
    AssetFrameSlider(state, busy)
    val selected = state.selected
    Text(stringResource(R.string.asset_target, selected?.label ?: "—"), color = Am.OnDarkDim)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ state.skipBack() }, enabled = !busy) { Text("◀") }
        Text("${state.timeMs} ms", Modifier.padding(top = 12.dp))
        TextButton({ state.skipForward() }, enabled = !busy) { Text("▶") }
        if (selected?.isMediaSwitch == true) TextButton({ state.edit { it.withoutMediaSwitchKey(selected.id, state.timeMs) } }, enabled = !busy) {
            Text(stringResource(R.string.asset_remove_key))
        }
    }
    if (selected?.isMediaSwitch == true) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            selected.mediaSwitchKeys.forEach { key ->
                TextButton({ state.frame = state.scene.frameAt(selected.timeOf(key.t)) }, enabled = !busy) {
                    Text("◆ ${selected.timeOf(key.t)} ms")
                }
            }
        }
    }
    val assets = state.scene.libraryMedia
    if (assets.isEmpty()) Text(stringResource(R.string.asset_empty), color = Am.OnDarkDim)
    assets.forEach { media ->
        val thumbnail by produceState<android.graphics.Bitmap?>(null, media.uri) {
            if (!media.type.startsWith("audio/")) value = mediaThumbnail(context, Uri.parse(media.uri))
        }
        Surface(color = Am.Well) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.size(54.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(media.title)
                        Text(media.type, color = Am.OnDarkDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({
                        when {
                            media.type.startsWith("audio/") -> state.addAudio(media)
                            media.type.startsWith("video/") -> state.addVideo(media)
                            else -> state.addImage(media)
                        }
                        close()
                    }, enabled = !busy) { Text(stringResource(R.string.asset_add_layer)) }
                    val switchable = selected != null && (selected.isMediaSwitch || (selected.kind == "shape" && selected.mediaFill != null)) &&
                        !media.type.startsWith("audio/") && state.timeMs in selected.startTime..selected.endTime
                    TextButton({ selected?.let { target -> state.edit { it.withMediaSwitch(target.id, state.timeMs, media) } } }, enabled = !busy && switchable) {
                        Text(stringResource(R.string.asset_key))
                    }
                    TextButton({ state.edit { it.withoutLibraryMedia(media.uri) } }, enabled = !busy) { Text(stringResource(R.string.asset_remove)) }
                }
            }
        }
    }
}

@Composable
private fun RigWorkspace(state: EditorState, busy: Boolean, work: (suspend () -> Unit) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    Row {
        TextButton({ tab = 0 }) { Text(stringResource(R.string.rig_bones), color = if (tab == 0) Am.Accent else Am.OnDarkDim) }
        TextButton({ tab = 1 }) { Text(stringResource(R.string.rig_saved), color = if (tab == 1) Am.Accent else Am.OnDarkDim) }
    }
    if (tab == 1) {
        SavedRigModels(state, busy, work, close)
        return
    }
    Text(stringResource(R.string.rig_help), color = Am.OnDarkDim)
    var boneId by remember { mutableStateOf(state.selected?.takeIf { it.isRigBone }?.id) }
    val bones = state.scene.layers.filter { it.isRigBone }
    val bone = boneId?.let { state.scene.layerAt(it, state.sampleMs) }
    var name by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("160") }
    var keyed by remember { mutableStateOf(true) }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rig_name)) }, singleLine = true)
    OutlinedTextField(length, { length = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rig_length)) }, singleLine = true)
    val validLength = length.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..10000f }
    Row {
        TextButton({ boneId = state.addRigBone(null, name.ifBlank { context.getString(R.string.rig_bone) }, validLength!!) }, enabled = !busy && validLength != null) {
            Text(stringResource(R.string.rig_root))
        }
        TextButton({ boneId = state.addRigBone(boneId, name.ifBlank { context.getString(R.string.rig_bone) }, validLength!!) }, enabled = !busy && bone != null && validLength != null) {
            Text(stringResource(R.string.rig_child))
        }
    }
    AssetFrameSlider(state, busy)
    if (bones.isNotEmpty()) RigDiagram(state, boneId)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        bones.forEach { b ->
            TextButton({ boneId = b.id; length = b.boneLength.toInt().toString() }, enabled = !busy) {
                Text(b.label, color = if (b.id == boneId) Am.Accent else Am.OnDarkDim)
            }
        }
    }
    Row {
        Checkbox(keyed, { keyed = it }, enabled = !busy)
        Text(stringResource(R.string.rig_keyed), Modifier.padding(top = 12.dp))
    }
    Row {
        TextButton({ state.skipBack() }, enabled = !busy) { Text("◀") }
        Text("${state.timeMs} ms", Modifier.padding(top = 12.dp))
        TextButton({ state.skipForward() }, enabled = !busy) { Text("▶") }
    }
    if (bone != null) {
        Text(stringResource(R.string.rig_rotation, bone.transform("rotation", 0f)[0].toInt()))
        Slider(bone.transform("rotation", 0f)[0].coerceIn(-180f, 180f), { angle ->
            state.edit("rig-rotation") { it.update(bone.id, state.sampleMs) { b -> b.withRigRotation(angle, keyed) } }
        }, enabled = !busy && state.timeMs in bone.startTime..bone.endTime, valueRange = -180f..180f, onValueChangeFinished = { state.endGesture() })
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton({ state.selectedId = bone.id; state.keyTarget = KeyTarget(PropRef.Rotation, "0"); state.addOpen = false; close() }, enabled = !busy) {
                Text(stringResource(R.string.rig_edit_timeline))
            }
            TextButton({ state.edit { it.update(bone.id) { b -> b.copy(node = b.node.with("trueBoneLength", "$validLength")) } } }, enabled = !busy && validLength != null) {
                Text(stringResource(R.string.rig_set_length))
            }
        }
        val art = state.chosenLayers.filterNot { it.isRigBone }.map { it.id }.toSet()
        Text(stringResource(R.string.rig_bind_help, art.size), color = Am.OnDarkDim)
        Row {
            TextButton({ state.bindRigLayers(art, bone.id) }, enabled = !busy && art.isNotEmpty()) { Text(stringResource(R.string.rig_bind)) }
            TextButton({ state.bindRigLayers(art, null) }, enabled = !busy && art.isNotEmpty()) { Text(stringResource(R.string.rig_unbind)) }
        }
        if (bone.node["parent"]?.toLongOrNull()?.let { state.scene.layer(it)?.isRigBone } == true) {
            var x by remember(bone.id) { mutableStateOf("200") }
            var y by remember(bone.id) { mutableStateOf("100") }
            var flip by remember { mutableStateOf(false) }
            Text(stringResource(R.string.rig_ik_help), color = Am.OnDarkDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(x, { x = it }, Modifier.weight(1f), label = { Text("X") }, singleLine = true)
                OutlinedTextField(y, { y = it }, Modifier.weight(1f), label = { Text("Y") }, singleLine = true)
            }
            Row {
                Checkbox(flip, { flip = it }, enabled = !busy)
                Text(stringResource(R.string.rig_flip), Modifier.padding(top = 12.dp))
            }
            val tx = x.toFloatOrNull()?.takeIf { it.isFinite() }
            val ty = y.toFloatOrNull()?.takeIf { it.isFinite() }
            TextButton({ work { state.poseTwoBoneIK(bone.id, tx!!, ty!!, flip, keyed) } }, enabled = !busy && tx != null && ty != null && state.timeMs in bone.startTime..bone.endTime) {
                Text(stringResource(R.string.rig_ik))
            }
        }
    }
    Row {
        TextButton({ state.captureRigRest() }, enabled = !busy && bones.isNotEmpty()) { Text(stringResource(R.string.rig_rest_save)) }
        TextButton({ state.restoreRigRest(keyed) }, enabled = !busy && bones.isNotEmpty()) { Text(stringResource(R.string.rig_rest_restore)) }
    }
}

@Composable
private fun RigDiagram(state: EditorState, selected: Long?) {
    val accent = Am.Accent
    val other = Am.OnDarkDim
    val scene = state.scene
    val bones = scene.layers.filter { it.isRigBone }
    val lines = bones.map { bone ->
        val at = scene.place(bone, state.sampleMs)
        val angle = Math.toRadians(at.rot.toDouble())
        Triple(bone.id, Offset(at.x, at.y), Offset(at.x + (cos(angle) * bone.boneLength * at.sx).toFloat(), at.y + (sin(angle) * bone.boneLength * at.sx).toFloat()))
    }
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Am.Well)) {
        val minX = minOf(0f, lines.minOf { minOf(it.second.x, it.third.x) })
        val minY = minOf(0f, lines.minOf { minOf(it.second.y, it.third.y) })
        val maxX = maxOf(scene.width.toFloat(), lines.maxOf { maxOf(it.second.x, it.third.x) })
        val maxY = maxOf(scene.height.toFloat(), lines.maxOf { maxOf(it.second.y, it.third.y) })
        val scale = minOf((size.width - 24) / (maxX - minX).coerceAtLeast(1f), (size.height - 24) / (maxY - minY).coerceAtLeast(1f))
        fun point(p: Offset) = Offset(12 + (p.x - minX) * scale, 12 + (p.y - minY) * scale)
        lines.forEach { (id, a, b) ->
            val color = if (id == selected) accent else other
            drawLine(color, point(a), point(b), 4.dp.toPx())
            drawCircle(color, 5.dp.toPx(), point(a))
            drawCircle(color, 3.dp.toPx(), point(b))
        }
    }
}

@Composable
private fun SavedRigModels(state: EditorState, busy: Boolean, work: (suspend () -> Unit) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val store = remember { rigModelStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    val saved by produceState(emptyList<app.absentmotion.library.SavedTemplate>(), revision) { value = withContext(Dispatchers.IO) { store.list() } }
    var title by remember { mutableStateOf(state.scene.title) }
    Text(stringResource(R.string.rig_save_help), color = Am.OnDarkDim)
    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rig_model_name)) }, singleLine = true)
    TextButton({ work {
        val model = state.scene.rigModel(state.chosenLayers.map { it.id }.toSet(), title.trim())
        store.add(model, 0, state.engine)
        revision++
    } }, enabled = !busy && title.isNotBlank() && state.scene.layers.isNotEmpty()) { Text(stringResource(R.string.rig_save)) }
    if (saved.isEmpty()) Text(stringResource(R.string.rig_empty), color = Am.OnDarkDim)
    saved.forEach { model ->
        Surface(color = Am.Well) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(model.title)
                Text(stringResource(R.string.rig_model_info, model.scene.layers.count { it.isRigBone }, model.scene.layers.size), color = Am.OnDarkDim)
                Row {
                    TextButton({ work {
                        val document = state.document
                        val localized = withContext(Dispatchers.IO) { store.localize(model.scene, state.store.mediaDir(state.projectId)) }
                        check(state.document == document)
                        state.insertRigModel(localized)
                        close()
                    } }, enabled = !busy) { Text(stringResource(R.string.asset_add_layer)) }
                    TextButton({ work { withContext(Dispatchers.IO) { store.rename(model.id, title.trim()) }; revision++ } }, enabled = !busy && title.isNotBlank()) { Text(stringResource(R.string.rig_rename)) }
                    TextButton({ work { withContext(Dispatchers.IO) { store.delete(model.id) }; revision++ } }, enabled = !busy) { Text(stringResource(R.string.asset_remove)) }
                }
            }
        }
    }
}

@Composable
private fun AssetFrameSlider(state: EditorState, busy: Boolean) {
    Slider(state.frame.toFloat().coerceIn(0f, state.scene.lastFrame.coerceAtLeast(1).toFloat()),
        { state.playing = false; state.frame = it.toInt() }, enabled = !busy,
        valueRange = 0f..state.scene.lastFrame.coerceAtLeast(1).toFloat())
}
