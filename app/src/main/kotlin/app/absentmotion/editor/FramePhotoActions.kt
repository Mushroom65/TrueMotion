package app.absentmotion.editor

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import app.absentmotion.truemotion.FramePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

fun EditorState.openFramePhoto(sourceVideo: Boolean = false) {
    playing = false
    motionPathOpen = false
    framePhotoSource = sourceVideo
    framePhotoOpen = true
    trueStudioOpen = false
    addOpen = false
}

suspend fun EditorState.captureFramePhoto(context: Context, sourceVideo: Boolean, seconds: Float): Long {
    playing = false
    endGesture()
    val before = document
    val path = groupPath
    val opened = scene
    val captureFrame = frame
    val at = timeMs
    val source = if (sourceVideo) selected?.takeIf { it.mediaFill?.video == true && it.activeAt(at) }
        ?: error(context.getString(R.string.true_photo_choose_video)) else null
    val record = source?.mediaFill?.let { fill -> opened.media(fill.uri)?.takeIf { it.width > 0 && it.height > 0 }
        ?: withContext(Dispatchers.IO) { mediaDeclaration(context, Uri.parse(fill.uri)) } }
    val captureBase = if (record == null) opened else app.absentmotion.model.Scene(opened.node.copy(
        children = opened.node.children.filterNot { it.name == "media" && it["uri"] == record.uri },
    )).withMedia(record)
    val folder = store.mediaDir(projectId).apply { mkdirs() }
    val file = File(folder, "frame-${UUID.randomUUID()}.png")
    var retained = false
    try {
        val title = context.getString(if (sourceVideo) R.string.true_photo_source_name else R.string.true_photo_composition_name)
        val label = source?.let { "${it.label} · $title" } ?: title
        val limit = minOf(4096, engine.maxPictureSize()).coerceAtLeast(1)
        val plan = FramePhotos.plan(captureBase, source?.id, captureFrame, seconds, file.toURI().toString(), label, Layer.newId(), limit)
            ?: error(context.getString(R.string.true_photo_cannot_capture))
        check(engine.stillPng(plan.capture.toXml(), at, plan.width, plan.height, file)) { context.getString(R.string.true_photo_cannot_capture) }
        coroutineContext.ensureActive()
        check(document.node === before.node && groupPath == path) { context.getString(R.string.true_photo_changed) }
        val media = MediaDeclaration(XmlNode("media", mapOf("uri" to file.toURI().toString(), "filename" to file.name,
            "type" to "image/png", "width" to "${plan.width}", "height" to "${plan.height}", "size" to "${file.length()}")))
        edit { it.withMedia(media).addAbove(plan.photo, source?.id ?: it.layers.lastOrNull()?.id ?: 0) }
        selectedId = plan.photo.id
        panel = EditorPanel.Tools
        addOpen = false
        framePhotoOpen = false
        retained = true
        return plan.photo.id
    } finally {
        if (!retained) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
    }
}
