package app.absentmotion.editor

import app.absentmotion.model.addEffect
import app.absentmotion.model.effects
import app.absentmotion.truemotion.ColorGrade

val EditorState.colorGradeEditing: Boolean
    get() = panel == EditorPanel.Effects && !easingOpen && selected?.effects?.getOrNull(effectIndex)?.get("id") == ColorGrade.ID

fun EditorState.openColorGrade() {
    val layer = selected ?: return
    if (layer.kind in setOf("audio", "camera", "nullobj")) return
    val definition = engine.effects.value.find(ColorGrade.ID)?.takeIf { it.available } ?: return
    playing = false
    endGesture()
    val existing = layer.effects.indexOfFirst { it["id"] == ColorGrade.ID }
    if (existing < 0) editSelected { it.addEffect(definition.instance()) }
    effectIndex = if (existing < 0) selected!!.effects.lastIndex else existing
    panel = EditorPanel.Effects
    easingOpen = false
    keypad = null
}
