package app.absentmotion.editor

import app.absentmotion.compat.EffectMigration
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.*
import app.absentmotion.truemotion.Particles
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ParticlesTest {
    private val assets=File("src/main/assets/effects")
    private val catalog=EffectCatalog.parse { File(assets,it).inputStream() }
    private val definition=catalog.find(Particles.ID)!!
    private fun scene()=Scene.parse("""<scene width="640" height="360" fps="30" totalTime="999"><shape id="1" label="Source" startTime="0" endTime="999" hidden="true" fillType="media" fillVideo="file:/a.mp4"/><shape id="2" label="Emitter" startTime="0" endTime="999"/></scene>""")
    private fun emitter(id:Long,source:Long)=Layer(XmlNode("shape",mapOf("id" to "$id","startTime" to "0","endTime" to "999"),listOf(definition.instance()))).let { Particles.withSource(it,0,source) }

    @Test fun createPersistsNativeGeneratorAndDoesNotBakeKeys() {
        val layer=Particles.create(scene(),500,definition,"Particles")
        val result=Scene.parse(scene().add(layer).toXml()).layer(layer.id)!!
        assertEquals(Particles.ID,result.effects.single()["id"])
        assertEquals(320f,result.location[0],.001f)
        assertEquals(180f,result.location[1],.001f)
        assertTrue(result.keyframeTimes().isEmpty())
        assertEquals(68,definition.params.size)
        assertEquals(definition.params.map { it.id }.toSet()-"sourceLayer",Particles.sections.flatten().toSet())
    }
    @Test fun generationModeCanChangeWithoutDeletingPhysicsOrKeys() {
        val mode=definition.params.single { it.id=="dimension" }
        assertEquals(listOf("2D","3D"),mode.options)
        assertEquals("-1.000000",mode.missingDefault)
        val old=emitter(2,0).withTilt(15f,60f)
        val changed=old.withEffect(0) { it.withEffectProperty("dimension","float","1",0f,0f..0f) }
        assertEquals(old.node.child("transform"),changed.node.child("transform"))
        assertEquals("1",Scene.parse(scene().update(2) { changed }.toXml()).layer(2)!!.effects[0].effectProperty("dimension")!!["value"])
    }
    @Test fun selectedHiddenVideoIsDecodedWithoutChangingSavedVisibilityOrAudio() {
        val original=scene().update(2) { it.addEffect(definition.instance()) }.update(2) { Particles.withSource(it,0,1) }
        val decoding=Particles.mediaScene(original)
        assertTrue(original.layer(1)!!.hidden)
        assertFalse(decoding.layer(1)!!.hidden)
        assertEquals(original.node["totalTime"],decoding.node["totalTime"])
        assertEquals(original.layer(2),decoding.layer(2))
        assertEquals(decoding,Particles.mediaScene(decoding))
    }
    @Test fun disabledGeneratorDoesNotDecodeItsHiddenSource() {
        val original=scene().update(2) { emitter(2,1).withEffect(0) { e -> e.with("hidden","true") } }
        assertEquals(original,Particles.mediaScene(original))
    }
    @Test fun indirectSourceCyclesAreUnavailableAndDecoderClosureTerminates() {
        val original=scene().update(1) { emitter(1,2).withHidden(true) }.update(2) { emitter(2,1) }
        assertFalse(Particles.canSource(original,2,1))
        assertFalse(Particles.canSource(original,2,2))
        assertTrue(Particles.canSource(original,2,0))
        assertFalse(Particles.mediaScene(original).layer(1)!!.hidden)
    }
    @Test fun duplicateRemapsTextureReferenceTogetherWithItsSource() {
        val original=scene().update(2) { emitter(2,1) }
        var next=100L
        val copies=copyLayers(original.layers,newId={next++})
        assertEquals(copies[0].id,Particles.source(copies[1].effects.single()))
        val single=copyLayers(listOf(original.layer(2)!!)).single()
        assertEquals(1L,Particles.source(single.effects.single()))
    }
    @Test fun legacyConversionPreservesXYZKeysFullSeedAndOldMissingDefaults() {
        val input=Scene.parse("""<scene width="640" height="360" totalTime="999" fps="30"><shape id="1" startTime="0" endTime="999"><effect id="lab.toji.particles3d"><property name="seed" type="float" value="9223372036854775807"/><property name="velocity" type="vec3" value="1,2,3"><kf t="0" v="1,2,3"/><kf t="1" v="4,5,6" e="linear"/></property></effect></shape></scene>""")
        val migrated=EffectMigration.parse(File(assets,"foreign.json").readText(),catalog).migrate(input)
        assertTrue(migrated.dropped.isEmpty())
        val effect=migrated.scene.layers.single().effects.single()
        assertEquals(Particles.ID,effect["id"])
        assertEquals("9223372036854775807",effect.effectProperty("seed")!!["value"])
        assertEquals("6.000000",effect.effectProperty("velocityZ")!!.keyframes.last().v)
        assertEquals(1f,effect.effectProperty("sizeEnd")!!["value"]!!.toFloat())
        assertEquals(1f,effect.effectProperty("opacityEnd")!!["value"]!!.toFloat())
    }
    @Test fun ownMediaSourceAndAspectPersistWithoutTouchingTransforms() {
        val before=scene().layer(1)!!.addEffect(definition.instance())
        val after=Particles.withSource(before,0,0,2f)
        assertEquals(before.node.child("transform"),after.node.child("transform"))
        assertEquals("true",after.effects.single().effectProperty("useTexture")!!["value"])
        assertEquals("2.0",after.effects.single().effectProperty("spriteAspect")!!["value"])
    }
}
