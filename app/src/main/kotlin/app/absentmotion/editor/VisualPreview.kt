package app.absentmotion.editor

import app.absentmotion.model.Scene

/** A temporary render transaction. It never enters Scene, history, autosave or export input. */
internal class VisualPreview(private val current: () -> Scene, private val draw: (Scene, String?) -> Unit) {
    private var owner: Any? = null
    fun show(token: Any, base: Scene, preview: Scene, xml: String? = null): Boolean {
        if (current() != base) return false
        owner = token
        draw(preview, xml)
        return true
    }
    fun clear(token: Any) { if (owner === token) clear() }
    fun clear() {
        if (owner == null) return
        owner = null
        draw(current(), null)
    }
}
