package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.*
import app.absentmotion.truemotion.Particles

internal val EditorState.particleEditing: Boolean get() = !easingOpen && panel == EditorPanel.Effects &&
    selected?.effects?.getOrNull(effectIndex)?.let(Particles::isEffect) == true

internal fun EditorState.addParticles() {
    val definition = engine.effects.value.find(Particles.ID)?.takeIf { it.available } ?: return
    playing = false; endGesture()
    val layer = Particles.create(scene, timeMs, definition, AppText.get(R.string.true_particles_title))
    edit { it.add(layer) }
    selectedId = layer.id
    effectIndex = 0; panel = EditorPanel.Effects
    addOpen = false; keypad = null; easingOpen = false
}
