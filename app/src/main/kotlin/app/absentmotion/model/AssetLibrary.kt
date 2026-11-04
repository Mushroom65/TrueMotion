package app.absentmotion.model

/** Library membership is explicit, so unused assets survive layer deletion and packaging. */
val Scene.libraryMedia: List<MediaDeclaration> get() = media.filter { it.node["trueLibrary"] == "true" }

fun Scene.withLibraryMedia(record: MediaDeclaration): Scene {
    val marked = MediaDeclaration(record.node.with("trueLibrary", "true"))
    return if (media(record.uri) == null) withMedia(marked) else Scene(node.copy(children = node.children.map {
        if (it.name == "media" && it["uri"] == record.uri) it.with("trueLibrary", "true") else it
    }))
}

fun Scene.withoutLibraryMedia(uri: String): Scene = Scene(node.copy(children = node.children.mapNotNull {
    if (it.name != "media" || it["uri"] != uri) it
    else if (uri in node.mediaUris()) it.with("trueLibrary", null) else null
}))

val Layer.isMediaSwitch: Boolean get() = kind == "embedScene" && node.child("trueMediaSwitch") != null
val Layer.mediaSwitchKeys: List<Keyframe> get() = node.child("trueMediaSwitch")?.keyframes.orEmpty()

/** Native, editable scene clips implement held media keys in preview AND all export paths. */
fun Scene.withMediaSwitch(id: Long, timeMs: Int, media: MediaDeclaration): Scene {
    val original = layer(id) ?: return this
    if (timeMs !in original.startTime..original.endTime) return this
    if (!media.type.startsWith("image/") && !media.type.startsWith("video/")) return this
    if (!original.isMediaSwitch && (original.kind != "shape" || original.mediaFill == null)) return this
    val span = (original.endTime - original.startTime).coerceAtLeast(1)
    val local = (timeMs - original.startTime).toFloat() / span
    val initial = original.mediaFill
    val source = original.node.child("trueSwitchSource") ?: original.node.copy(name = "trueSwitchSource",
        attrs = original.node.attrs - setOf("id", "parent", "clippingMask", "blending"),
        children = original.node.children.filterNot { it.name in setOf("transform", "effect", "shadow", "speedMap", "trueAssetLibrary", "trueRigBind") })
    val first = if (original.isMediaSwitch) original.mediaSwitchKeys else listOf(Keyframe(0f, initial!!.uri,
        extra = mapOf((if (initial.video) "fillVideo" else "fillImage") to initial.uri)))
    val key = Keyframe(local, media.uri, extra = mapOf((if (media.type.startsWith("video/")) "fillVideo" else "fillImage") to media.uri))
    val keys = (first.filter { frameAt(original.timeOf(it.t)) != frameAt(timeMs) } + key).sortedBy { it.t }
    return withMedia(media).update(id) { makeMediaSwitch(it, source, keys) }
}

fun Scene.withoutMediaSwitchKey(id: Long, timeMs: Int): Scene {
    val layer = layer(id)?.takeIf { it.isMediaSwitch } ?: return this
    val keys = layer.mediaSwitchKeys
    val here = keys.indexOfFirst { frameAt(layer.timeOf(it.t)) == frameAt(timeMs) }
    // Keep the initial exposure; removing later keys extends the previous exposure.
    if (here <= 0) return this
    return update(id) { makeMediaSwitch(it, it.node.child("trueSwitchSource")!!, keys.filterIndexed { i, _ -> i != here }) }
}

private fun Scene.makeMediaSwitch(layer: Layer, source: XmlNode, keys: List<Keyframe>): Layer {
    val length = (layer.endTime - layer.startTime).coerceAtLeast(1)
    val sorted = keys.sortedBy { it.t }
    val initial = sorted.lastOrNull { it.t <= 0f } ?: sorted.firstOrNull()
    val exposures = listOfNotNull(initial?.copy(t = 0f)) + sorted.filter { it.t > 0f && it.t <= 1f }
    val clips = exposures.mapIndexedNotNull { i, key ->
        val start = (layer.timeOf(key.t) - layer.startTime).coerceAtLeast(0)
        val end = exposures.getOrNull(i + 1)?.let { layer.timeOf(it.t) - layer.startTime - 1 } ?: length
        if (end < start) return@mapIndexedNotNull null
        val video = key.extra["fillVideo"] != null
        val geometry = source.retimed(layer.startTime, layer.endTime, layer.startTime + start, layer.startTime + end)
        var clip = Layer(geometry.copy(name = "shape", attrs = geometry.attrs - setOf("fillImage", "fillVideo", "inTime", "outTime", "speed") + mapOf(
            "id" to "${Layer.newId()}", "label" to (media(key.v)?.title ?: ""), "startTime" to "$start", "endTime" to "$end",
            "fillType" to "media", (if (video) "fillVideo" else "fillImage") to key.v,
        ))).withTransform("location", width / 2f, height / 2f, 0f)
        if (video) clip = clip.copy(node = clip.node.with("inTime", "0").with("speed", "1").with("loop", "true"))
        clip.node
    }
    val nested = Scene.create("", width, height, fps, 0).node.with("totalTime", "$length").with("retime", "off").copy(children = clips)
    val outerChildren = layer.node.children.filter { it.name !in setOf("trueMediaSwitch", "trueSwitchSource", "scene", "property", "fillColor", "path-stroke", "speedMap") }
    return layer.copy(node = XmlNode("embedScene", layer.node.attrs - setOf("s", "fillImage", "fillVideo", "fillType", "inTime", "outTime", "speed", "loop"),
        outerChildren + source + XmlNode("trueMediaSwitch").withKeyframes(keys) + nested))
}

/** Timeline edits can move/delete replacement diamonds; keep native exposures synchronized. */
internal fun Scene.refreshMediaSwitches(previous: Scene): Scene = Scene(node.copy(children = node.children.map { child ->
    if (child.name !in Scene.LAYER_TAGS) return@map child
    val layer = Layer(child)
    val old = previous.layer(layer.id) ?: return@map child
    if (layer.isMediaSwitch && (layer.node.child("trueMediaSwitch") != old.node.child("trueMediaSwitch") ||
            layer.node.child("trueSwitchSource") != old.node.child("trueSwitchSource") ||
            layer.endTime - layer.startTime != old.endTime - old.startTime)) {
        val source = layer.node.child("trueSwitchSource") ?: return@map child
        val keys = layer.mediaSwitchKeys.ifEmpty { old.mediaSwitchKeys.take(1) }
        makeMediaSwitch(layer, source, keys).node
    } else child
}))

val MediaSwitchRef = PropRef(PropRef.Kind.Child, "trueMediaSwitch")
val Layer.assetUris: List<String> get() = node.child("trueAssetLibrary")?.children.orEmpty().mapNotNull { it["fillImage"] ?: it["fillVideo"] }

fun Scene.layerAssets(layer: Layer): List<MediaDeclaration> {
    val own = layer.assetUris
    val current = layer.mediaFill?.uri ?: layer.mediaSwitchKeys.firstOrNull()?.v
    val uris = if (own.isEmpty()) listOfNotNull(current) + libraryMedia.map { it.uri } else own
    return uris.distinct().mapNotNull(::media).filter { it.type.startsWith("image/") || it.type.startsWith("video/") }
}

fun Scene.withLayerAsset(id: Long, media: MediaDeclaration): Scene {
    val layer = layer(id) ?: return this
    val existing = layerAssets(layer)
    val records = (existing + media).distinctBy { it.uri }
    val library = XmlNode("trueAssetLibrary", children = records.map { record ->
        XmlNode("asset", mapOf((if (record.type.startsWith("video/")) "fillVideo" else "fillImage") to record.uri))
    })
    return withMedia(media).update(id) { it.copy(node = it.node.withChild("trueAssetLibrary", library)) }
}

fun Scene.activeLayerAsset(layer: Layer, time: Int): MediaDeclaration? {
    val keys = layer.mediaSwitchKeys
    val uri = if (keys.isEmpty()) layer.mediaFill?.uri else keys.lastOrNull { layer.timeOf(it.t) <= time }?.v ?: keys.first().v
    return uri?.let(::media)
}

fun Scene.onAssetKey(layer: Layer, time: Int): Boolean = layer.mediaSwitchKeys.any { frameAt(layer.timeOf(it.t)) == frameAt(time) }

fun Scene.withStaticLayerAsset(id: Long, media: MediaDeclaration): Scene = withLayerAsset(id, media).update(id) { layer ->
    val original = layer.node.child("trueSwitchSource")
    if (original == null) layer.copy(node = layer.node.with("fillType", "media").with("fillImage", media.uri.takeUnless { media.type.startsWith("video/") })
        .with("fillVideo", media.uri.takeIf { media.type.startsWith("video/") }))
    else {
        val placement = layer.node.children.filter { it.name in setOf("transform", "effect", "shadow", "trueAssetLibrary", "trueRigBind") }
        val node = XmlNode("shape", original.attrs + layer.node.attrs, original.children + placement)
            .with("fillType", "media").with("fillImage", media.uri.takeUnless { media.type.startsWith("video/") })
            .with("fillVideo", media.uri.takeIf { media.type.startsWith("video/") })
        layer.copy(node = node)
    }
}

fun Scene.toggleAssetKey(id: Long, time: Int): Scene {
    val layer = layer(id) ?: return this
    val active = activeLayerAsset(layer, time) ?: return this
    if (!onAssetKey(layer, time)) return withMediaSwitch(id, time, active)
    return if (layer.mediaSwitchKeys.size <= 1) withStaticLayerAsset(id, active) else {
        val keys = layer.mediaSwitchKeys.filterNot { frameAt(layer.timeOf(it.t)) == frameAt(time) }
        update(id) { makeMediaSwitch(it, it.node.child("trueSwitchSource")!!, keys) }
    }
}

/** Selecting another thumbnail never creates an implicit replacement key. */
fun Scene.chooseLayerAsset(id: Long, time: Int, media: MediaDeclaration): Scene {
    val layer = layer(id) ?: return this
    if (!layer.isMediaSwitch) return withStaticLayerAsset(id, media)
    if (!onAssetKey(layer, time)) return this
    return withMediaSwitch(id, time, media)
}
