package app.absentmotion.compat

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode

/** Alight pivot is an offset from the layer centre; native projects use an anchor coordinate.
 * Keep authored values and curves intact and carry the convention with each imported transform. */
internal fun XmlNode.withImportedGeometry(legacyPackage: Boolean = false): XmlNode {
    fun visit(node: XmlNode, legacy: Boolean): XmlNode {
        if(node.name=="scene") {
            val source=node["am"]
            val own=if(source.isNullOrEmpty()) legacy else source.startsWith("com.alightcreative")
            return node.copy(children=node.children.map { visit(it,own) })
        }
        var own=node
        if(legacy && node.name in Scene.LAYER_TAGS) {
            val transform=node.child("transform")?:XmlNode("transform")
            own=node.withChild("transform",transform.with("originMode","alight"))
        }
        return own.copy(children=own.children.map { visit(it,legacy) })
    }
    return visit(this,legacyPackage)
}
