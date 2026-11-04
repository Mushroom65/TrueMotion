package app.absentmotion.export

import app.absentmotion.library.*
import app.absentmotion.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

class LibraryPackageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun unusedLibraryAndFutureReplacementSurvivePackageRoundTripToAnotherProject() = runBlocking {
        val root = temp.newFolder()
        fun media(name: String) = File(root, "media/source/$name.png").let { file ->
            file.parentFile.mkdirs(); file.writeText(name)
            MediaDeclaration(XmlNode("media", mapOf("uri" to file.toURI().toString(), "filename" to file.name, "type" to "image/png")))
        }
        val a = media("a"); val b = media("b"); val unused = media("unused")
        val scene = Scene.create("Actor", 100, 100, 30, 0).withLibraryMedia(a).withLibraryMedia(b).withLibraryMedia(unused)
            .add(Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "999", "fillType" to "media", "fillImage" to a.uri))))
            .withMediaSwitch(1, 500, b)
        for (alight in listOf(false, true)) {
            val bytes = ByteArrayOutputStream()
            if (alight) writeAlightPackage(scene, scene.toXml(), bytes, MediaAccess.forPhone(root), { File(URI(it)).inputStream() }, {})
            else writeProjectPackage(scene, scene.toXml(), bytes, MediaAccess.forPhone(root), { File(URI(it)).inputStream() }, {})
            val other = temp.newFolder()
            val projects = ProjectStore(other)
            val report = Importer(projects, access = MediaAccess.forPhone(other)).import { bytes.toByteArray().inputStream() }.single()
            val imported = projects.load(report.id)!!
            assertEquals(setOf("a", "b", "unused"), imported.libraryMedia.map { File(URI(it.uri)).readText() }.toSet())
            val layer = imported.layers.single()
            assertEquals(listOf("a", "b"), layer.nested!!.layers.map { File(URI(it.mediaFill!!.uri)).readText() })
            assertEquals(listOf("a", "b"), layer.mediaSwitchKeys.map { File(URI(it.v)).readText() })
            assertTrue(imported.node.storedMediaUris().all { it.startsWith(projects.mediaDir(report.id).toURI().toString()) })
        }
    }
}
