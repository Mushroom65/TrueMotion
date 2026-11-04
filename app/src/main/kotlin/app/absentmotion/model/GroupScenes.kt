package app.absentmotion.model

/** A nested editor is a view of the original document, never a second project. */
internal fun Scene.groupScene(path: List<Long>): Scene? {
    var current = this
    for (id in path) {
        val nested = current.layer(id)?.nested ?: return null
        current = current.media.fold(nested) { scene, record -> scene.withMedia(record) }
    }
    return current
}

internal fun Scene.validGroupPath(path: List<Long>): List<Long> {
    var current = this
    return path.takeWhile { id ->
        val nested = current.layer(id)?.nested
        if (nested == null) false else { current = nested; true }
    }
}

/** Keep inherited media available in the editor without duplicating it into every group. */
internal fun Scene.withGroupScene(path: List<Long>, changed: Scene): Scene {
    if (path.isEmpty()) return changed
    val id = path.first()
    val group = layer(id) ?: return this
    val original = group.nested ?: return this
    val inherited = media.associateBy { it.uri }
    val visible = media.fold(original) { scene, record -> scene.withMedia(record) }
    val edited = if (path.size == 1) changed else visible.withGroupScene(path.drop(1), changed)
    val ownMedia = original.media.map { it.uri }.toSet()
    val stored = Scene(edited.node.copy(children = edited.node.children.filterNot {
        it.name == "media" && it["uri"] !in ownMedia && inherited[it["uri"]]?.node == it
    }))
    val merged = edited.media.fold(this) { scene, record -> scene.withMedia(record) }
    return merged.update(id) {
        var node = it.node.withChild("scene", stored.node)
        // Manual edits turn generated exposures into an ordinary group; never overwrite them later.
        if (it.isMediaSwitch && stored != original) node = node.withChild("trueMediaSwitch", null).withChild("trueSwitchSource", null)
        // Linked elements are refreshed from their source on load. A local edit becomes an instance copy.
        if (stored != original && it.link != null) node = node.with("trueSourceLink", it.link).with("link", null)
        it.copy(node = node)
    }
}

/** Extending a normal group holds its last frame; explicit retiming and speed stay intact. */
internal fun Layer.withExtendedGroupHold(previousEnd: Int): Layer {
    val inner = nested ?: return this
    if (endTime <= previousEnd || inner.retime != "off" || inner.node["trueTimingExplicit"] == "true") return this
    if (endTime.toLong() - startTime + nestedCut <= inner.totalTime) return this
    val speed = node.child("speedMap")?.valueAt(0f)?.toDoubleOrNull() ?: node["speed"]?.toDoubleOrNull() ?: 1.0
    if (speed != 1.0 || node.child("speedMap")?.isKeyframed == true) return this
    return copy(node = node.withChild("scene", inner.node.with("retime", "freeze")))
}

/** Repair groups already extended by older builds, including groups nested in other groups. */
internal fun Scene.withGroupHolds(): Scene {
    var changed = false
    val children = node.children.map { child ->
        val layer = Layer(child)
        val inner = layer.nested ?: return@map child
        val repaired = layer.copy(node = child.withChild("scene", inner.withGroupHolds().node))
            .withExtendedGroupHold(layer.startTime + inner.totalTime - layer.nestedCut)
        if (repaired.node != child) { changed = true; repaired.node } else child
    }
    return if (changed) Scene(node.copy(children = children)) else this
}
