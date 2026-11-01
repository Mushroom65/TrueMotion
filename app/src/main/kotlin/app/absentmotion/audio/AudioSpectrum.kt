package app.absentmotion.audio

import kotlin.math.*

/** Stereo power FFT. Only frequency envelopes are retained; PCM is streamed and discarded. */
internal object AudioSpectrum {
    const val BINS = 64
    const val SIZE = 4096
    const val MAX_FRAMES = 8192
    const val MIN_HZ = 20.0

    data class Data(val startMs: Int, val stepMs: Double, val bands: List<FloatArray>, val maxHz: Double) {
        val count get() = bands.firstOrNull()?.size ?: 0
        val bytes get() = count.toLong() * bands.size * 4
        fun responses(gain: Float, gate: Float, attack: Float, release: Float, normalize: Boolean): List<AudioEnvelope.Response> {
            val maximum = if (normalize) max(bands.maxOfOrNull { it.maxOrNull() ?: 0f } ?: 0f, 1e-6f) else 1f
            return bands.map { raw ->
                var previous = 0f
                val levels = FloatArray(raw.size) { i ->
                    val target = (((raw[i] / maximum - gate).coerceAtLeast(0f) / (1f-gate).coerceAtLeast(.001f)) * gain).coerceIn(0f,1f)
                    val tau = if (target > previous) attack else release
                    val follow = if (tau <= 0) 0f else exp(-stepMs / tau).toFloat()
                    previous = target + follow * (previous-target)
                    previous
                }
                AudioEnvelope.Response(startMs,stepMs,levels,0f,release)
            }
        }
    }

    fun frequency(bin: Double, maxHz: Double): Double = MIN_HZ * (maxHz/MIN_HZ).pow(bin/(BINS-1))

    class Stream(private val rate: Int, private val startMs: Int, durationMs: Int) {
        init { require(rate >= 8000 && durationMs >= 0) }
        private val hop = max(rate/25, ceil(durationMs.toDouble()*rate/1000/MAX_FRAMES).toInt().coerceAtLeast(1))
        private val ring = Array(2) { DoubleArray(SIZE) }
        private val real = DoubleArray(SIZE)
        private val imag = DoubleArray(SIZE)
        private val power = DoubleArray(SIZE/2+1)
        private val window = DoubleArray(SIZE) { .5-.5*cos(2*PI*it/(SIZE-1)) }
        private val weight = window.sum()/2
        private var cursor = 0
        private var received = 0L
        private var realFrames = 0L
        private var next = SIZE/2L + hop/2
        private var capacity = 128
        private var values = Array(BINS) { FloatArray(capacity) }
        private var used = 0
        private var finished = false
        private val maxHz = rate/2.0

        fun add(stereo: FloatArray) {
            check(!finished)
            require(stereo.size%2==0)
            for (i in stereo.indices step 2) push(stereo[i].toDouble(),stereo[i+1].toDouble())
            realFrames += stereo.size/2
        }

        private fun push(left: Double,right: Double) {
            ring[0][cursor] = if(left.isFinite()) left.coerceIn(-8.0,8.0) else 0.0
            ring[1][cursor] = if(right.isFinite()) right.coerceIn(-8.0,8.0) else 0.0
            cursor = (cursor+1) and (SIZE-1)
            received++
            if(received >= next) { analyze();next += hop }
        }

        private fun analyze() {
            if(used>=MAX_FRAMES) return
            power.fill(0.0)
            for(channel in 0..1) {
                for(i in 0 until SIZE) real[i] = ring[channel][(cursor+i) and (SIZE-1)]*window[i]
                imag.fill(0.0)
                fft(real,imag)
                for(i in power.indices) power[i] += (real[i]*real[i]+imag[i]*imag[i])/(2*weight*weight)
            }
            if(used==capacity) { capacity=min(MAX_FRAMES,capacity*2);values=Array(BINS) { values[it].copyOf(capacity) } }
            for(bin in 0 until BINS) {
                val center=frequency(bin.toDouble(),maxHz)*SIZE/rate
                val low=if(bin==0) 0.0 else frequency(bin-.5,maxHz)*SIZE/rate
                val high=if(bin==BINS-1) SIZE/2.0 else frequency(bin+.5,maxHz)*SIZE/rate
                var energy=0.0
                for(i in floor(low).toInt().coerceAtLeast(0)..ceil(high).toInt().coerceAtMost(SIZE/2)) {
                    val overlap=(min(high,i+.5)-max(low,i-.5)).coerceIn(0.0,1.0)
                    energy += power[i]*overlap
                }
                // Sub-bin integration is stable at low frequencies and does not create empty bands.
                if(high-low<1) {
                    val i=center.toInt().coerceIn(0,SIZE/2-1);val f=center-i
                    energy=power[i]*(1-f)+power[i+1]*f
                }
                values[bin][used]=sqrt(energy.coerceAtLeast(0.0)).toFloat()
            }
            used++
        }

        fun finish(): Data {
            check(!finished);finished=true
            val wanted=ceil(realFrames.toDouble()/hop).toInt().coerceAtMost(MAX_FRAMES)
            while(used<wanted) push(0.0,0.0)
            return Data(startMs,hop*1000.0/rate,values.map { it.copyOf(used) },maxHz)
        }
    }

    private fun fft(real: DoubleArray,imag: DoubleArray) {
        var j=0
        for(i in 1 until real.size) {
            var bit=real.size shr 1
            while(j and bit!=0) { j=j xor bit;bit=bit shr 1 }
            j=j xor bit
            if(i<j) { val a=real[i];real[i]=real[j];real[j]=a }
        }
        var length=2
        while(length<=real.size) {
            val angle=-2*PI/length;val wr=cos(angle);val wi=sin(angle)
            for(start in real.indices step length) {
                var ur=1.0;var ui=0.0
                for(k in 0 until length/2) {
                    val a=start+k;val b=a+length/2
                    val vr=real[b]*ur-imag[b]*ui;val vi=real[b]*ui+imag[b]*ur
                    real[b]=real[a]-vr;imag[b]=imag[a]-vi;real[a]+=vr;imag[a]+=vi
                    val next=ur*wr-ui*wi;ui=ur*wi+ui*wr;ur=next
                }
            }
            length=length shl 1
        }
    }
}
