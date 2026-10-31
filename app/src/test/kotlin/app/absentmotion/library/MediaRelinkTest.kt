package app.absentmotion.library

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRelinkTest {
    private fun media(vararg attrs: Pair<String, String>) = MediaDeclaration(XmlNode("media", mapOf("uri" to "content://gone/1", *attrs)))
    private val known = media("filename" to "IMG_0001.jpg", "size" to "2048")

    @Test fun oneMatchingFileIsRelinked() {
        assertEquals(7L, MediaRelink.pick(known, sequenceOf(7L)))
    }

    @Test fun twoMatchingFilesLeaveTheMediaMissing() {
        assertNull(MediaRelink.pick(known, sequenceOf(7L, 8L)))
        assertNull(MediaRelink.pick(known, generateSequence(1L) { it + 1 }))
    }

    @Test fun noMatchOrNoKnownSizeOrNameLeavesTheMediaMissing() {
        assertNull(MediaRelink.pick(known, emptySequence()))
        assertNull(MediaRelink.pick(media("filename" to "IMG_0001.jpg"), sequenceOf(7L)))
        assertNull(MediaRelink.pick(media("size" to "2048"), sequenceOf(7L)))
    }
    @Test fun onlyPublicMediaItemsCanRestoreTheirOriginalAddress() {
        assertEquals("content://media/external/images/media/42",restorableMediaStoreUri("blocked-content://media/external/images/media/42"))
        assertEquals("content://media/external/file/12",restorableMediaStoreUri("blocked-content://media/external/file/12"))
        for(uri in listOf("file:///data/user/0/app.truemotion/files/preferences.xml","content://private.provider/secret/42","content://media@evil/external/file/1","content://media/external/file/-1","content://media/external/file/1?evil=1","content://media/external/file/../../secret")) assertNull(uri,restorableMediaStoreUri("blocked-$uri"))
        assertEquals("clip.mp4",importedMediaFilename("blocked-file:///storage/emulated/0/Movies/clip.mp4"))
        assertEquals("",importedMediaFilename("blocked-content://media/external/video/media/42"))
    }
}
