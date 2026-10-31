package app.absentmotion.export

import app.absentmotion.library.*
import app.absentmotion.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

class AlightRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun editableSceneAndMediaTravelThroughBothPackageFormats() = runBlocking {
        val source = tmp.newFolder("source")
        val media = File(source, "media/p1/picture.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 7, 23, 42)) }
        val uri = media.toURI().toString()
        val scene = Scene.parse("""<scene title="Round trip" width="320" height="180" fps="60" totalTime="999"><media uri="$uri" filename="picture.png"/><shape id="1" startTime="0" endTime="999" fillImage="$uri"><transform><location><kf t="0" v="10,20,0"/><kf t="1" v="40,50,0"/></location></transform><effect id="absent.blur"><property name="strength" type="float" value="7"/></effect></shape></scene>""")
        for (alight in listOf(true, false)) {
            val out = ByteArrayOutputStream()
            if (alight) writeAlightPackage(scene, scene.toXml(), out, MediaAccess.forPhone(source), { File(URI(it)).inputStream() }, {})
            else writeProjectPackage(scene, scene.toXml(), out, MediaAccess.forPhone(source), { File(URI(it)).inputStream() }, {})
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals(if (alight) "true-motion-project" else "absentmotion-package", manifest.getString("format"))
            assertTrue(entries.containsKey(if (alight) "scene.xml" else "project.xml"))
            val target = tmp.newFolder(if (alight) "alight" else "absproj")
            val store = ProjectStore(target)
            val report = Importer(store, access = MediaAccess.forPhone(target)).import { out.toByteArray().inputStream() }.single()
            val restored = store.load(report.id)!!
            assertEquals(scene.width, restored.width)
            assertEquals(scene.fps, restored.fps)
            assertEquals(scene.layers.single().node.child("transform"), restored.layers.single().node.child("transform"))
            assertEquals(scene.layers.single().effects, restored.layers.single().effects)
            val importedUri = restored.layers.single().node["fillImage"]!!
            assertTrue(MediaAccess.forPhone(target).allows(importedUri))
            assertArrayEquals(media.readBytes(), File(URI(importedUri)).readBytes())
        }
    }
}
