package app.truemotion.vector

object VectorPortable {
    fun mediaUris(marker: String): Set<String> = VectorMediaPortable.all(VectorAnimation.decode(marker))
        .mapNotNull { it.mediaUri.takeIf(String::isNotEmpty) }.toSet()
    fun remap(marker: String, change: (String) -> String): String {
        val document = VectorAnimation.decode(marker)
        var changed = false
        for (shape in VectorMediaPortable.all(document)) {
            if (shape.mediaUri.isNotEmpty()) {
                val next = change(shape.mediaUri)
                if (next != shape.mediaUri) { shape.mediaUri = next; changed = true }
            }
        }
        return if (changed) VectorAnimation.marker(document) else marker
    }
}
