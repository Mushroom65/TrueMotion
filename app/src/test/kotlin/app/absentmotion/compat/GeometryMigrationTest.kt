package app.absentmotion.compat

import app.absentmotion.model.XmlNode
import org.junit.Assert.*
import org.junit.Test

class GeometryMigrationTest {
    @Test fun foreignGroupsRetainAllAuthoredValuesAndTheirCoordinateConvention() {
        val before=XmlNode.parse("""<scene am="com.alightcreative.tojimotionlab/5.0" width="320" height="320"><embedScene id="1"><transform><location value="10,20,0"/><pivot value="0,90"/></transform><scene width="80" height="80"><shape id="2"><transform><location><kf t="0" v="40,40,0"/><kf t="1" v="60,20,0" e="bounce 0.1 1"/></location></transform></shape></scene></embedScene></scene>""")
        val after=before.withImportedGeometry()
        assertEquals("alight",after.children.single().child("transform")!!["originMode"])
        val old=before.children.single().child("scene")!!.children.single().child("transform")!!
        val changed=after.children.single().child("scene")!!.children.single().child("transform")!!
        assertEquals(old.children,changed.children)
        assertEquals("alight",changed["originMode"])
        assertEquals(after,after.withImportedGeometry())
    }
    @Test fun nativeProjectsKeepTheirExistingAnchorConvention() {
        val native=XmlNode.parse("""<scene am="app.absentmotion" width="320" height="320"><shape id="1"><transform><pivot value="30,40"/></transform></shape></scene>""")
        assertEquals(native,native.withImportedGeometry())
        assertEquals(native,native.withImportedGeometry(true))
    }
}
