package app.truemotion.port

import android.content.Context
import android.net.Uri
import app.absentmotion.editor.*
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.*
import app.absentmotion.model.*
import app.absentmotion.ui.editor.addPicked
import app.absentmotion.ui.editor.cacheMatches
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

internal object UpstreamV4Probe {
    fun run(context: Context, directory: File): JSONObject = runBlocking {
        val store=ProjectStore(context)
        val id="true-v4-preview-regression"
        val source=checkNotNull(store.load("true-motion-path-regression")).withTitle("True 4.0 Preview")
        val engine=EngineThread(0,context)
        val saver=ProjectSaver(id,store,Recovery(File(directory,"v4-recovery")),failed={ error->throw AssertionError("V4 save",error) })
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val result=JSONObject()
        try {
            val state=EditorState(id,source,store,engine,AppSettings(context),saver)
            withContext(Dispatchers.Main) {
                PreviewCache.fit=.5f;PreviewCache.checkerCell=8f
                PreviewCache.render(state,context,scope)
            }
            withTimeout(30_000) { while(PreviewCache.phase==PreviewCache.Phase.Rendering) delay(10) }
            check(PreviewCache.phase==PreviewCache.Phase.Ready && PreviewCache.covers(0) && PreviewCache.covers(59))
            check(cacheMatches(state,engine.mediaRevision.value))
            val player=checkNotNull(PreviewCache.player)
            player.want(15,false)
            withTimeout(10_000) { while(player.shown?.frame!=15) delay(5) }
            val bitmap=player.shown!!.bitmap
            val cached=ByteBuffer.allocate(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(it) }.array()
            val original=engine.exportSession(source.toXml()) { still(source.frameStart(15),player.width,player.height) }!!
            flattenOnChecker(original,player.width,player.height,8f)
            val mean=original.indices.sumOf { abs((original[it].toInt() and 255)-(cached[it].toInt() and 255)).toDouble() }/original.size
            check(mean<8) { "Cached path frame differs from the engine: $mean" }
            val frames=PreviewCache.last-PreviewCache.first+1
            withContext(Dispatchers.Main) {
                state.edit { it.update(701) { l->l.withFill("color",0xffff0000.toInt()) } }
                check(!cacheMatches(state,engine.mediaRevision.value))
                state.undo()
                check(cacheMatches(state,engine.mediaRevision.value))
            }
            result.put("renderedFrames",frames).put("cachedFrameMeanError",mean).put("editInvalidatesAndUndoReuses",true)
            result.put("diskCapBytes",diskBudget(Long.MAX_VALUE))
            check(diskBudget(Long.MAX_VALUE)==2L*1024*1024*1024)
            withContext(Dispatchers.Main) { PreviewCache.clear() }
            check(context.cacheDir.listFiles { f->f.name.startsWith("preview-cache-") }.orEmpty().isEmpty())
            withContext(Dispatchers.Main) { PreviewCache.render(state,context,scope);PreviewCache.cancel() }
            withTimeout(15_000) { while(PreviewCache.phase==PreviewCache.Phase.Rendering) delay(10) }
            withContext(Dispatchers.Main) { PreviewCache.clear() }
            check(context.cacheDir.listFiles { f->f.name.startsWith("preview-cache-") }.orEmpty().isEmpty())
            result.put("cancelAndClearRemoveTemporaryFiles",true)
            store.save(id,source)

            val gifId="true-v4-gif-regression"
            val folder=store.mediaDir(gifId).apply { mkdirs() }
            val fixture=File(folder,"animated-v4.gif")
            context.createPackageContext("app.truemotion.test",0).assets.open(fixture.name).use { input->fixture.outputStream().use(input::copyTo) }
            val blank=Scene.create("True 4.0 GIF",128,128,30,0)
            val gifSaver=ProjectSaver(gifId,store,Recovery(File(directory,"v4-gif-recovery")),failed={ error->throw AssertionError("GIF save",error) })
            val gif=EditorState(gifId,blank,store,engine,AppSettings(context),gifSaver)
            withContext(Dispatchers.Main) { gif.addPicked(context,Uri.fromFile(fixture));gif.endGesture() }
            check(gif.scene.layers.size==1 && gif.scene.layers.single().nested!=null) { "Transparent GIF must become an animated clip with a matte" }
            val scene=gif.scene
            val colors=JSONArray()
            for((index,ms) in listOf(50,150,250,350,450,550).withIndex()) {
                val bytes=engine.exportSession(scene.toXml()) { still(ms,128,128) }!!
                val counts=IntArray(3)
                var transparent=0
                for(i in bytes.indices step 4) {
                    val rgba=IntArray(4) { bytes[i+it].toInt() and 255 }
                    if(rgba[3]<15) transparent++
                    if(rgba[3]>128) for(channel in 0..2) if(rgba[channel]>120 && rgba[channel]>rgba[(channel+1)%3]+40 && rgba[channel]>rgba[(channel+2)%3]+40) counts[channel]++
                }
                check(counts[index%3]>500 && transparent>1000) { "GIF frame $ms lost animation/alpha: ${counts.toList()}, transparent=$transparent" }
                colors.put(JSONArray(counts.toList()))
            }
            gifSaver.flush().get()
            val restored=checkNotNull(store.load(gifId))
            check(restored.layers.single().nested!=null)
            check(engine.stillPng(restored.toXml(),150,128,128,File(directory,"gif-v4-frame.png")))
            result.put("gifColorCountsOverTwoLoops",colors).put("gifAlpha",true).put("gifSavedAndReopened",true)
            File(directory,"gif-v4.xml").writeText(restored.toXml())
            val titles=listOf("en","ru","zh-CN").map { tag->
                val config=android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
                context.createConfigurationContext(config).getString(app.absentmotion.R.string.preview_cache_render)
            }
            check(titles[0]=="Render preview" && titles[2]=="预渲染预览")
            result.put("previewUILanguages",JSONArray(titles))
            result
        } finally {
            withContext(Dispatchers.Main) { PreviewCache.clear() }
            scope.cancel();engine.close();saver.flush().get()
        }
    }
}
