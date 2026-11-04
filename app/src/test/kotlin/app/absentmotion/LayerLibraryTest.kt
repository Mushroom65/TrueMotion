package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class LayerLibraryTest {
    private fun media(uri:String)=MediaDeclaration(XmlNode("media",mapOf("uri" to uri,"type" to "image/png")))
    private fun scene():Scene=Scene.parse("""<scene width="300" height="300" fps="30"><media uri="a" type="image/png"/><shape id="1" s=".rect" startTime="0" endTime="2999" fillType="media" fillImage="a"/><shape id="2" s=".rect" startTime="0" endTime="2999" fillType="media" fillImage="a"/></scene>""")

    @Test fun importingAnAssetAddsItOnlyToTheSelectedLayerWithoutCreatingAnimation() {
        val scene=scene().withLayerAsset(1,media("b"))
        assertEquals(listOf("a","b"),scene.layerAssets(scene.layer(1)!!).map { it.uri })
        assertEquals(listOf("a"),scene.layerAssets(scene.layer(2)!!).map { it.uri })
        assertEquals(setOf("a"),scene.node.imageUris())
        assertTrue(scene.node.storedMediaUris().contains("b"))
        assertFalse(scene.layer(1)!!.isMediaSwitch)
        assertEquals("a",scene.layer(1)!!.mediaFill!!.uri)
    }

    @Test fun replacementRequiresAnExplicitKeyOnAnAnimatedLayer() {
        val original=scene().withLayerAsset(1,media("b"))
        val keyed=original.toggleAssetKey(1,1000).chooseLayerAsset(1,1000,media("b"))
        assertEquals(listOf("a","b"),keyed.layer(1)!!.mediaSwitchKeys.map { it.v })
        assertEquals(keyed,keyed.chooseLayerAsset(1,2000,media("a")))
        val next=keyed.toggleAssetKey(1,2000).chooseLayerAsset(1,2000,media("a"))
        assertEquals(listOf("a","b","a"),next.layer(1)!!.mediaSwitchKeys.map { it.v })
        assertEquals("b",next.activeLayerAsset(next.layer(1)!!,1500)!!.uri)
    }

    @Test fun removingTheOnlyKeyRestoresAnOrdinaryMediaLayerAndKeepsThePalette() {
        val original=scene().withLayerAsset(1,media("b"))
        val static=original.toggleAssetKey(1,0).chooseLayerAsset(1,0,media("b")).toggleAssetKey(1,0)
        val layer=static.layer(1)!!
        assertFalse(layer.isMediaSwitch)
        assertEquals("shape",layer.kind)
        assertEquals(".rect",layer.shape)
        assertEquals("b",layer.mediaFill!!.uri)
        assertEquals(listOf("a","b"),static.layerAssets(layer).map { it.uri })
    }

    @Test fun libraryImagesTravelWithTheLayerAndAllUrisRelinkTogether() {
        val scene=scene().withLayerAsset(1,media("unused")).toggleAssetKey(1,1000)
        val mapped=Scene(scene.node.mapMediaUris { "new-$it" })
        val layer=mapped.layer(1)!!
        assertTrue(layer.assetUris.all { it.startsWith("new-") })
        assertTrue(layer.node.storedMediaUris().contains("new-unused"))
        assertTrue(layer.mediaSwitchKeys.all { it.v.startsWith("new-") })
        assertEquals(layer.assetUris,Scene.parse(mapped.toXml()).layer(1)!!.assetUris)
    }
}
