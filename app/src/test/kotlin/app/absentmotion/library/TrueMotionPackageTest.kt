package app.absentmotion.library

import app.absentmotion.model.mediaUris
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrueMotionPackageTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(corrupt: Boolean = false): ByteArray {
        val media = byteArrayOf(1, 2, 3, 4, 5)
        val xml = """<scene title="True" width="800" height="600" fps="30" totalTime="1000"><shape id="17" startTime="0" endTime="999" fillType="media" fillImage="alight://media/image.png"/></scene>""".toByteArray()
        val manifest = JSONObject().put("format", "toji-alight-project").put("version", 1).put("sceneSha256", hash(xml))
            .put("media", org.json.JSONArray().put(JSONObject().put("uri", "alight://media/image.png").put("path", "media/image.png").put("sha256", hash(media))))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, bytes) in listOf("scene.xml" to xml, "scene.original.xml" to xml, "media/image.png" to if (corrupt) byteArrayOf(9) else media,
                "manifest.json" to manifest.toString().toByteArray())) {
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun choosesCanonicalSceneAndRelinksAlightMedia() {
        val store = ProjectStore(tmp.newFolder())
        val bytes = archive()
        val report = Importer(store).import { bytes.inputStream() }.single()
        assertFalse(report.template)
        val scene = store.load(report.id)!!
        assertEquals(800, scene.width)
        val file = java.io.File(java.net.URI(scene.node.mediaUris().single()))
        assertTrue(file.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), file.readBytes())
    }

    @Test fun refusesChangedMediaWithoutLeavingPartialProject() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val bytes = archive(corrupt = true)
        try { Importer(store).import { bytes.inputStream() }; fail("Expected checksum rejection") } catch (_: ImportFailed) { }
        assertTrue(root.walkTopDown().filter { it.isFile }.none())
    }
}
