package app.absentmotion.model

import kotlin.math.exp
import kotlin.math.ln

internal object PlaybackSpeed {
    const val Min = SLOWEST_SPEED
    const val Max = 100f
    val presets = listOf(.25f, .5f, 1f, 2f, 4f, 10f, 25f, 50f, 100f)
    fun position(speed: Float): Float = ((ln(speed.coerceIn(Min, Max)) - ln(Min)) / (ln(Max) - ln(Min))).coerceIn(0f, 1f)
    fun value(position: Float): Float = exp(ln(Min) + position.coerceIn(0f, 1f) * (ln(Max) - ln(Min))).coerceIn(Min, Max)
}

internal fun Scene.withPlaybackSpeed(layer: Layer, value: Float, anchor: Float, timeMs: Int): Layer {
    if (!value.isFinite()) return layer
    val map = layer.node.child("speedMap") ?: XmlNode("speedMap", mapOf("value" to (layer.node["speed"] ?: "1")))
    val snap = layer.atVideoTime(timeMs, fps).snap
    return layer.withVideoSpeedMap(map.withValueAt(anchor, value.coerceIn(PlaybackSpeed.Min, PlaybackSpeed.Max).toString(), snap), keepContentEnd = true)
        .let { changed ->
            // Exporters draw complete project frames. Keep short clips visible and hold the source tail.
            val last = maxOf(firstShownFrame(changed.startTime), frameAt((changed.endTime - 1).coerceAtLeast(changed.startTime)))
            changed.withTimes(changed.startTime, frameEnd(last))
        }
}
