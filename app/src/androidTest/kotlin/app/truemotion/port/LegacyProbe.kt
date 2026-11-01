package app.truemotion.port

import android.content.Context
import android.os.SystemClock
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.graphics.Bitmap
import java.nio.ByteBuffer

internal object LegacyProbe {
    fun run(context:Context,engine:NativeEngine,directory:File,full:Boolean):JSONObject {
        val catalog=EffectCatalog.parse { context.assets.open("effects/$it") }
        val legacy=catalog.definitions.filter { it.dialect=="legacy" }
        val wanted=File(context.filesDir,"legacy014-thumbkeys.json").takeIf { !full && it.isFile }?.let { f->JSONArray(f.readText()).let { a->(0 until a.length()).map { a.getString(it) }.toSet() } }
        check(legacy.size==1746) { "Only ${legacy.size} legacy definitions parsed" }
        val report=JSONObject().put("count",legacy.size);val compiled=JSONArray();val frames=JSONArray()
        for(effect in legacy) {
            File(directory,"current.txt").writeText("compile ${effect.key} ${effect.name}")
            val status=engine.loadEffect(effect.runtimeXml(lazy=false))
            compiled.put(JSONObject().put("id",effect.key).put("status",status))
            if(compiled.length()%25==0)File(directory,"compile.json").writeText(compiled.toString())
        }
        File(directory,"compile.json").writeText(compiled.toString())
        check((0 until compiled.length()).all { compiled.getJSONObject(it).getString("status")=="ok" }) { "Legacy native compile failures" }
        for(effect in legacy.filter { if(wanted!=null)it.key in wanted else full || it.legacyScript.isNotEmpty() || it.textures.any { t->t.source=="image" } || it.params.any { p->p.kind=="quaternion" } }) {
            File(directory,"current.txt").writeText("render ${effect.key} ${effect.name}")
            val text=effect.legacyScript.contains(".text")
            val body=if(text) """<text typeface="sans-serif" size="15" width="64"><content>True Motion</content></text>""" else """<path d="M-14,-16 L14,-16 L14,16 L-14,16 Z"/>"""
            val xml="""<scene width="64" height="64" fps="30" totalTime="1999" bgcolor="#00000000"><shape id="1" startTime="0" endTime="1999" fillType="color"><transform><location value="32,32,0"/></transform><fillColor value="#ffed335d"/>$body${effect.instance().toXml().substringAfter("?>")}</shape></scene>"""
            val start=SystemClock.elapsedRealtime();engine.setScene(xml)
            val pixels=checkNotNull(engine.still(750.0,64,64)) { effect.key }
            if(wanted!=null) {
                val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                File(directory,"${effect.key}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            frames.put(JSONObject().put("id",effect.key).put("ms",SystemClock.elapsedRealtime()-start).put("alphaPixels",(3 until pixels.size step 4).count { (pixels[it].toInt() and 255)>0 }).put("hash",pixels.contentHashCode()))
            if(frames.length()%25==0)File(directory,"renders.json").writeText(frames.toString())
        }
        File(directory,"renders.json").writeText(frames.toString())
        report.put("compiled",compiled.length()).put("rendered",frames.length()).put("full",full)
        return report
    }
}
