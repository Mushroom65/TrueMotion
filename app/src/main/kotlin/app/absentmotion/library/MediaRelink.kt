package app.absentmotion.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.replaceMedia
import app.absentmotion.model.withLegacyExifOrientation
import app.absentmotion.model.storedMediaUris
import app.absentmotion.model.XmlNode
import app.absentmotion.media.exifTag

object MediaRelink {
    fun opens(context: Context, uri: String) =
        runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } }.getOrNull() == true

    fun find(context: Context, media: MediaDeclaration, allowNameOnly: Boolean = false): String? {
        if (media.filename.isEmpty() || media.size <= 0 && !allowNameOnly) return null
        val collection = when {
            media.type.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            media.type.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            media.type.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        return runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" +
                    (if (media.size > 0) " AND ${MediaStore.MediaColumns.SIZE} = ?" else "") +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,2,3)",
                (listOf(media.filename) + if (media.size > 0) listOf(media.size.toString()) else emptyList()).toTypedArray(), null,
            )?.use { c ->
                val ids=generateSequence { if (c.moveToNext()) c.getLong(0) else null }
                if(allowNameOnly) ids.take(2).toList().singleOrNull() else pick(media,ids)
            }
                ?.let { Uri.withAppendedPath(collection, it.toString()).toString() }
        }.getOrNull()
    }

    internal fun pick(media: MediaDeclaration, candidates: Sequence<Long>): Long? =
        if (media.filename.isEmpty() || media.size <= 0) null else candidates.take(2).toList().singleOrNull()

    fun relink(context: Context, scene: Scene): Scene {
        val declared = scene.media.associateBy { it.uri }
        val references = scene.node.storedMediaUris().map { uri ->
            declared[uri] ?: MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "filename" to importedMediaFilename(uri))))
        }
        return (scene.media + references).distinctBy { it.uri }.fold(scene) { s, m ->
            val uri = if (opens(context, m.uri)) m.uri else restorableMediaStoreUri(m.uri)?.takeIf { opens(context, it) }
                ?: find(context, m, allowNameOnly = true)?.takeIf { opens(context, it) }
            if (uri == null) s else {
                val linked = if (uri == m.uri && !s.isMissing(uri)) s
                    else s.replaceMedia(m.uri, MediaDeclaration(m.node.with("uri", uri).with("missing", null)))
                if (m.type.startsWith("image/") && m.orientation == 0)
                    linked.withLegacyExifOrientation(uri, exifTag(context.contentResolver, Uri.parse(uri))) else linked
            }
        }
    }
}

/** Only restore public MediaStore item addresses, never arbitrary private file/provider URIs. */
internal fun restorableMediaStoreUri(value:String):String? = runCatching {
    val text=value.removePrefix("blocked-")
    val uri=java.net.URI(text)
    if(uri.scheme!="content" || uri.authority!="media" || uri.query!=null || uri.fragment!=null) return null
    val p=uri.path.split('/').filter(String::isNotEmpty)
    if(p.firstOrNull() !in setOf("external","external_primary","internal")) return null
    val item=p.size==4 && p[1] in setOf("images","video","audio") && p[2]=="media" || p.size==3 && p[1]=="file"
    text.takeIf { item && p.last().toLongOrNull()?.let { it>0 }==true }
}.getOrNull()

internal fun importedMediaFilename(value:String):String = runCatching {
    val path=java.net.URI(value.removePrefix("blocked-")).path ?: return ""
    path.substringAfterLast('/').substringAfterLast(':').takeIf { '.' in it && it.length<=255 }.orEmpty()
}.getOrDefault("")
