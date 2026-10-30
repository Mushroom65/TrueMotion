package app.absentmotion.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Streaming stereo-energy analysis. It retains 100 values/second, rather than the decoded audio. */
internal object AudioEnvelope {
    enum class Band { Full, Bass, Mid, High }

    data class Data(val startMs: Int, val stepMs: Double, val bands: List<FloatArray>) {
        val count get() = bands[0].size
        fun peak(band: Band): Float = bands[band.ordinal].maxOrNull() ?: 0f

        fun response(band: Band, gain: Float, gate: Float, attackMs: Float, releaseMs: Float, normalize: Boolean, invert: Boolean): Response {
            val raw = bands[band.ordinal]
            val peak = if (normalize) max(peak(band), 1e-6f) else 1f
            var level = 0f
            val levels = FloatArray(raw.size) { i ->
                val target = (((raw[i] / peak - gate).coerceAtLeast(0f) / (1f - gate).coerceAtLeast(.001f)) * gain).coerceIn(0f, 1f)
                val tau = if (target > level) attackMs else releaseMs
                val follow = if (tau <= 0) 0f else exp(-stepMs / tau).toFloat()
                level = target + follow * (level - target)
                if (invert) 1f - level else level
            }
            return Response(startMs, stepMs, levels, if (invert) 1f else 0f, releaseMs)
        }
    }

    data class Response(val startMs: Int, val stepMs: Double, val levels: FloatArray, val silence: Float = 0f, val releaseMs: Float = 0f) {
        fun at(timeMs: Double): Float {
            if (levels.isEmpty() || timeMs < startMs) return silence
            if (timeMs >= startMs + levels.size * stepMs) {
                val elapsed = timeMs - (startMs + levels.size * stepMs)
                return if (releaseMs <= 0) silence else silence + (levels.last() - silence) * exp(-elapsed / releaseMs).toFloat()
            }
            val position = ((timeMs - startMs) / stepMs - .5).coerceAtLeast(0.0)
            val i = position.toInt().coerceAtMost(levels.lastIndex)
            val next = levels.getOrElse(i + 1) { levels[i] }
            return levels[i] + (next - levels[i]) * (position - i).toFloat().coerceIn(0f, 1f)
        }
    }

    class Stream(private val rate: Int, private val startMs: Int) {
        init { require(rate >= 8000) }
        private val hop = (rate / 100).coerceAtLeast(1)
        private val lowAlpha = (1 - exp(-2 * PI * 250 / rate)).toFloat()
        private val midAlpha = (1 - exp(-2 * PI * 2000 / rate)).toFloat()
        private val low = FloatArray(2)
        private val mid = FloatArray(2)
        private val sums = DoubleArray(4)
        private var have = 0
        private var used = 0
        private var capacity = 1024
        private var values = Array(4) { FloatArray(capacity) }

        fun add(stereo: FloatArray) {
            require(stereo.size % 2 == 0)
            for (i in stereo.indices step 2) {
                for (c in 0..1) {
                    val x = stereo[i + c].takeIf { it.isFinite() }?.coerceIn(-8f, 8f) ?: 0f
                    low[c] += lowAlpha * (x - low[c])
                    mid[c] += midAlpha * (x - mid[c])
                    val middle = mid[c] - low[c]
                    val high = x - mid[c]
                    sums[0] += x.toDouble() * x
                    sums[1] += low[c].toDouble() * low[c]
                    sums[2] += middle.toDouble() * middle
                    sums[3] += high.toDouble() * high
                }
                if (++have == hop) flush()
            }
        }

        private fun flush() {
            if (have == 0) return
            if (used == capacity) {
                capacity *= 2
                values = Array(4) { values[it].copyOf(capacity) }
            }
            for (band in 0..3) { values[band][used] = sqrt(sums[band] / (have * 2)).toFloat(); sums[band] = 0.0 }
            have = 0; used++
        }

        fun finish(): Data { flush(); return Data(startMs, hop * 1000.0 / rate, values.map { it.copyOf(used) }) }
    }
}
