package app.absentmotion.truemotion

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Edits the existing effect properties, so native keyframes, easing and scene XML stay authoritative. */
object ColorGrade {
    const val ID = "lab.true.grade"
    val primary = listOf("exposure", "contrast", "saturation", "vibrance", "temperature", "tint", "shadows", "highlights", "blackPoint", "whitePoint")
    val tones = listOf("lift", "gamma", "gain")
    val bands = listOf("Red", "Yellow", "Green", "Cyan", "Blue", "Magenta")
    fun wheelKeys(tone: String) = listOf(tone, "${tone}R", "${tone}G", "${tone}B")
    fun curveKeys(channel: String) = (1..3).map { "curve$channel$it" }
    fun hslKeys(band: String) = listOf("hsl${band}H", "hsl${band}S", "hsl${band}L")

    fun value(layer: Layer, index: Int, definition: EffectDefinition, id: String): Float {
        val param = definition.params.first { it.id == id }
        return layer.effects.getOrNull(index)?.effectProperty(id)?.valueAt(layer.t)?.finiteFloat()
            ?: param.missingDefault.toFloat()
    }

    fun change(layer: Layer, index: Int, definition: EffectDefinition, values: Map<String, Float>): Layer {
        if (layer.effects.getOrNull(index)?.get("id") != ID) return layer
        val params = definition.params.associateBy { it.id }
        return layer.withEffect(index) { effect ->
            values.entries.fold(effect) { current, (id, value) ->
                val param = params[id]
                if (param == null || !value.isFinite()) current else current.withEffectProperty(id, param.type,
                    formatFloats(floatArrayOf(value.coerceIn(param.min, param.max))), layer.t, layer.snap)
            }
        }
    }

    /** Reset only the sampled values of a section; animated tracks and other sections are retained. */
    fun reset(layer: Layer, index: Int, definition: EffectDefinition, ids: List<String>): Layer =
        change(layer, index, definition, definition.params.filter { it.id in ids }.associate { it.id to it.default.toFloat() })

    data class Wheel(val hue: Float, val amount: Float)

    // Pure colour math; no Android Color dependency, shared by controls and host regression tests.
    fun rgb(hue: Float, amount: Float, mean: Float = 0f): FloatArray {
        val h = ((hue % 360f + 360f) % 360f) / 60f
        val x = 1f - abs(h % 2f - 1f)
        val channels = when (h.toInt()) {
            0 -> floatArrayOf(1f, x, 0f); 1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x); 3 -> floatArrayOf(0f, x, 1f)
            4 -> floatArrayOf(x, 0f, 1f); else -> floatArrayOf(1f, 0f, x)
        }
        val center = channels.average().toFloat()
        return FloatArray(3) { ((channels[it] - center) * amount.coerceIn(0f, 1f) + mean).coerceIn(-1f, 1f) }
    }

    fun wheel(rgb: FloatArray): Wheel {
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val high = max(r, max(g, b)); val low = min(r, min(g, b)); val d = high - low
        if (d < 1e-6f) return Wheel(0f, 0f)
        val sector = when (high) { r -> (g - b) / d; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
        return Wheel((sector * 60f + 360f) % 360f, d.coerceIn(0f, 1f))
    }

    /** Same fixed quarter-point, piecewise-linear curve used by true-grade.sksl. */
    fun curve(x: Float, samples: List<Float>): Float {
        require(samples.size == 3)
        val p = listOf(0f) + samples + 1f
        val scaled = x.coerceIn(0f, 1f) * 4f
        val segment = scaled.toInt().coerceAtMost(3)
        return p[segment] + (p[segment + 1] - p[segment]) * (scaled - segment)
    }
}
