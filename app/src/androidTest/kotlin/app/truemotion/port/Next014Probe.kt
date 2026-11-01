package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.AudioEffectRuntime
import app.absentmotion.engine.EngineThread
import app.absentmotion.editor.PreviewCache
import app.absentmotion.engine.NativeEngine
import app.absentmotion.editor.withPivotMoved
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.*
import app.absentmotion.truemotion.Spectrum
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal object Next014Probe {
    fun run(context:Context,engine:NativeEngine,output:File):JSONObject=runBlocking {
        val report=JSONObject(); val catalog=EffectCatalog.parse { context.assets.open("effects/$it") }
        check(catalog.definitions.size==2546) { "Catalog: ${catalog.definitions.size}" }
        val loaded=catalog.loadInto(engine::loadEffect)
        check(loaded.definitions.all { it.available }) { loaded.definitions.filterNot { it.available }.joinToString { "${it.key}: ${it.status}" } }
        report.put("catalog",loaded.definitions.size)
        val spectrum=catalog.find(Spectrum.ID)!!
        val store=ProjectStore(context);val media=store.mediaDir("spectrum-014-regression").apply { mkdirs() }
        fun wave(name:String,freq:Double):File {
            val rate=44100;val samples=rate*4;val b=ByteBuffer.allocate(44+samples*4).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(b.capacity()-8).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate*4).putShort(4).putShort(16).put("data".toByteArray()).putInt(samples*4)
            for(i in 0 until samples) {val s=(sin(i*2*PI*freq/rate)*24000).toInt().toShort();b.putShort(s).putShort((-s).toShort())}
            return File(media,name).also { it.writeBytes(b.array()) }
        }
        val low=wave("110hz.wav",110.0).toURI().toString();val high=wave("6000hz.wav",6000.0).toURI().toString()
        var effect=spectrum.instance().withEffectProperty("sourceLayer","string","2",0f,0f..0f)
        effect=effect.withEffectProperty("width","float","280",0f,0f..0f).withEffectProperty("height","float","150",0f,0f..0f)
        val scene=Scene.parse("""<scene title="Spectrum 014" width="320" height="320" fps="30" totalTime="3999" bgcolor="#00000000"><media uri="$low" duration="4000000" filename="110hz.wav"/><media uri="$high" duration="4000000" filename="6000hz.wav"/><shape id="1" label="Spectrum" startTime="0" endTime="3999" fillType="color"><transform><location value="160,160,0"/></transform><path d="M-10,-10 L10,-10 L10,10 L-10,10 Z"/>${effect.toXml().substringAfter("?>")}</shape><audio id="2" src="$low" label="110 Hz stereo" startTime="0" endTime="3999" inTime="0" outTime="4000"/><audio id="3" src="$high" label="6000 Hz stereo" startTime="0" endTime="3999" inTime="0" outTime="4000"/></scene>""")
        val live=EngineThread(0,context)
        try {
            live.setScene(scene)
            withTimeout(60_000) {
                while(true) {
                    val p=live.previewForTest(320,320)
                    if(!live.audioEffects.value.pending && p!=null && (3 until p.size step 4).count { (p[it].toInt() and 255)>8 }>50)break
                    delay(30)
                }
            }
            report.put("spectrumWhilePausedWithoutExport",true)
        } finally { live.close() }
        val encoder=PreviewCache.Encoder(2,1)
        try {
            val original=byteArrayOf(255.toByte(),0,0,128.toByte(),0,255.toByte(),0,255.toByte())
            val encoded=checkNotNull(encoder.encode(original))
            check(encoded.take(8)==listOf(137.toByte(),80.toByte(),78.toByte(),71.toByte(),13.toByte(),10.toByte(),26.toByte(),10.toByte()))
            val decoded=android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.size)
            check(decoded.getPixel(0,0)==0x80ff0000.toInt()) { "Alpha/color changed in the cache" }
            check(decoded.getPixel(1,0)==0xff00ff00.toInt())
            check(original[3]==128.toByte()) { "Encoder flattened the input" }
            decoded.recycle();report.put("cacheLosslessAlphaPNG",true)
        } finally {encoder.bitmap.recycle()}
        val runtime=AudioEffectRuntime(context)
        val prepared=runtime.prepare(scene)
        check(prepared.signals.size==64)
        engine.setAudioSignals(prepared.signals)
        engine.setScene(prepared.scene.toXml())
        fun render(s:Scene):ByteArray {engine.setScene(s.toXml());return checkNotNull(engine.still(1000.0,320,320))}
        fun save(p:ByteArray,name:String) {val b=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);b.copyPixelsFromBuffer(ByteBuffer.wrap(p));File(output,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()}
        val modes=JSONArray();val hashes=mutableSetOf<Int>()
        for(mode in 0..6) {
            val s=prepared.scene.update(1) { it.withEffect(0) { n->n.withEffectProperty("style","float","$mode",0f,0f..0f) } }
            val p=render(s);val alpha=(3 until p.size step 4).count { (p[it].toInt() and 255)>8 }
            check(alpha>50) { "Empty spectrum $mode" };hashes+=p.contentHashCode();save(p,"spectrum-$mode");modes.put(alpha)
        }
        check(hashes.size==7) { "Spectrum styles did not differ: ${hashes.size}" }
        val first=render(prepared.scene)
        val other=runtime.prepare(scene.update(1) { it.withEffect(0) { e->e.withEffectProperty("sourceLayer","string","3",0f,0f..0f) } })
        engine.setAudioSignals(other.signals);val second=render(other.scene)
        check(!first.contentEquals(second)) { "Audio source selector ignored" }
        engine.setAudioSignals(prepared.signals)
        check(first.contentEquals(render(Scene.parse(prepared.scene.toXml()))))
        val ui=store.create(scene);report.put("spectrumProject",ui).put("spectrumModesAlpha",modes).put("audioSourceChangesBands",true)
        report.put("noGeneratedKeys",scene.layers.none { l->l.effects.any { e->e.children.any { n->n.children.any { it.name=="kf" } } } })
        val parented=Scene.parse("""<scene width="400" height="400" fps="30" totalTime="1999"><shape id="10" startTime="0" endTime="1999"><transform><location value="200,200,0"/><rotation value="25"/><scale value="1.5,0.8"/></transform><path d="M-30,-30 L30,-30 L30,30 L-30,30 Z"/></shape><shape id="11" parent="10" startTime="0" endTime="1999"><transform><location value="55,-35,0"/><pivot value="4,-8"/><rotation value="-15"/><scale value="0.7,1.3"/></transform><path d="M-20,-20 L20,-20 L20,20 L-20,20 Z"/></shape></scene>""")
        engine.setScene(parented.toXml());val before=engine.layerMatrix(11,500.0)!!
        val anchored=parented.withPivotMoved(10,500,37f,-23f)
        engine.setScene(anchored.toXml());val after=engine.layerMatrix(11,500.0)!!
        val drift=before.indices.maxOf { abs(before[it]-after[it]) };check(drift<.02) { "Anchor edit moved child: $drift" }
        val child=anchored.layer(11)!!
        val cp=child.pivot;val cs=child.scale;val cr=child.transform("rotation",0f)[0]*PI/180
        val local=doubleArrayOf(cos(cr)*cs[0],-sin(cr)*cs[1],child.location[0]-cos(cr)*cs[0]*cp[0]+sin(cr)*cs[1]*cp[1],sin(cr)*cs[0],cos(cr)*cs[1],child.location[1]-sin(cr)*cs[0]*cp[0]-cos(cr)*cs[1]*cp[1],0.0,0.0,1.0)
        var error=0.0
        for(rotation in listOf(-90f,0f,47f,180f))for(scale in listOf(.5f,1f,2.5f)) {
            val s=anchored.update(10) { it.withTransform("rotation",rotation).withTransform("scale",scale,scale*.7f) }
            engine.setScene(s.toXml());val pm=engine.layerMatrix(10,500.0)!!;val cm=engine.layerMatrix(11,500.0)!!
            for(i in 0..8) error=max(error,abs(cm[i]-(0..2).sumOf { k->pm[i/3*3+k]*local[k*3+i%3] }))
        }
        check(error<.02) { "Parent anchor mismatch after transform: $error" }
        report.put("anchorEditChildDrift",drift).put("anchorParentMatrixError",error)
        report
    }
}
