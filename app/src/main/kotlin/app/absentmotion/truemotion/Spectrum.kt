package app.absentmotion.truemotion

import app.absentmotion.model.*

internal object Spectrum {
    const val ID = "lab.true.spectrum"
    data class Config(val source: Long? = null, val volume: Boolean = true, val attack: Float = 15f, val release: Float = 150f)

    fun active(node: XmlNode) = node.name=="effect" && node["id"]==ID && node["hidden"]!="true"
    fun config(node: XmlNode): Config {
        fun raw(name:String)=node.effectProperty(name)?.get("value")
        fun number(name:String,default:Float,min:Float,max:Float)=raw(name)?.finiteFloat()?.coerceIn(min,max) ?: default
        return Config(raw("sourceLayer")?.toLongOrNull()?.takeIf { it>0 },raw("respectVolume")!="false",
            number("attackMs",15f,0f,2000f),number("releaseMs",150f,0f,5000f))
    }
    fun configurations(scene: Scene): List<Pair<Scene,Config>> {
        val found=mutableListOf<Pair<Scene,Config>>()
        fun visit(node:XmlNode,parent:Scene) {
            val local=if(node.name=="scene") Scene(node) else parent
            if(active(node)) found += local to config(node)
            node.children.forEach { visit(it,local) }
        }
        visit(scene.node,scene)
        return found
    }
    fun bind(scene:Scene,key:(Scene,Config)->String?,audioKey:((Scene,AudioMotion.Settings)->String?)?=null):Scene {
        fun visit(node:XmlNode,parent:Scene):XmlNode {
            val local=if(node.name=="scene") Scene(node) else parent
            val attrs=when {
                active(node) -> node.with("signalKey",key(local,config(node))).attrs
                AudioReactive.active(node) && audioKey!=null -> node.with("signalKey",audioKey(local,AudioReactive.settings(node))).attrs
                else -> node.attrs
            }
            return node.copy(attrs=attrs,children=node.children.map { visit(it,local) })
        }
        return Scene(visit(scene.node,scene))
    }
}
