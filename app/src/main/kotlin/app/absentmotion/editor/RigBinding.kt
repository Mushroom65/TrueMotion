package app.absentmotion.editor

import app.absentmotion.model.*
import kotlin.math.*

private val IdentityRigFrame = Affine(1.0,0.0,0.0,1.0,0.0,0.0)
internal fun RigBinding.affine() = Affine(a,b,c,d,tx,ty)

/** Match the native 2D parent matrix, including anchors, mirrored scale and shear. */
internal fun Scene.rigAffine(layer: Layer, time: Int, depth: Int = 0): Affine {
    val at=layer.at(time,fps)
    val l=at.location;val p=at.pivot;val s=at.scale;val skew=at.transform("skew",0f,0f)
    val imported=at.node.child("transform")?.get("originMode")=="alight"
    val scale=Affine.scale(s[0].toDouble(),s[1].toDouble())
    val shear=Affine.skew(tan(skew[0].toDouble()),tan(skew[1].toDouble()))
    val own=Affine.translate((l[0]+if(imported)p[0] else 0f).toDouble(),(l[1]+if(imported)p[1] else 0f).toDouble()) *
        Affine.rotate((at.transform("rotation",0f)[0]+at.pathAngle()).toDouble()) *
        (if(imported) scale*shear else shear*scale) * Affine.translate(-p[0].toDouble(),-p[1].toDouble())
    val parent=if(depth<32) layer.node["parent"]?.toLongOrNull()?.let(::layer)?.let { rigAffine(it,time,depth+1) } else null
    return (parent?:IdentityRigFrame)*(layer.rigBinding?.affine()?:IdentityRigFrame)*own
}

internal fun Scene.rigParentAffine(layer: Layer,time: Int): Affine =
    (layer.node["parent"]?.toLongOrNull()?.let(::layer)?.let { rigAffine(it,time) }?:IdentityRigFrame)*(layer.rigBinding?.affine()?:IdentityRigFrame)

/** Bind at the playhead, preserving the artwork's original keys byte for byte. */
internal fun Scene.withRigParent(id: Long,parentId: Long?,time: Int): Scene {
    val art=layer(id)?:return this
    if(art.node["parent"]?.toLongOrNull()==parentId) return this
    val parent=parentId?.let(::layer)
    if(parentId!=null && parent?.isBone!=true) return this
    if(parent!=null && chain(parent).any { it.id==id }) return this
    val oldFrame=rigParentAffine(art,time)
    val inverse=(parent?.let { rigAffine(it,time) }?:IdentityRigFrame).inverse()?:return this
    val offset=inverse*oldFrame
    if(abs(offset.a*offset.d-offset.b*offset.c)<1e-12) return this
    val oldZ=(art.node["parent"]?.toLongOrNull()?.let(::layer)?.let { place(it,time).z }?:0f)+(art.rigBinding?.z?:0f)
    val z=oldZ-(parent?.let { place(it,time).z }?:0f)
    val bind=RigBinding(offset.a,offset.b,offset.c,offset.d,offset.tx,offset.ty,z)
    return update(id) { it.copy(node=it.node.with("parent",parentId?.toString()).withChild("trueRigBind",bind.node())) }
}

internal fun Scene.withRigChildrenDetached(parent: Long,time: Int): Scene =
    (layers+bones).filter { it.node["parent"]=="$parent" }.fold(this) { scene,child -> scene.withRigParent(child.id,null,time) }

internal fun Scene.rigPlace(layer: Layer,time: Int): Place {
    val m=rigAffine(layer,time);val at=layer.at(time,fps);val pivot=at.pivot
    val anchor=m.map(Pt(pivot[0],pivot[1]));val sx=hypot(m.a,m.b)
    val parent=layer.node["parent"]?.toLongOrNull()?.let(::layer)
    val z=(parent?.let { place(it,time).z }?:0f)+(layer.rigBinding?.z?:0f)+at.location[2]
    return Place(anchor.x,anchor.y,pivot[0],pivot[1],sx.toFloat(),if(sx>1e-12)((m.a*m.d-m.b*m.c)/sx).toFloat() else 0f,Math.toDegrees(atan2(m.b,m.a)).toFloat(),z)
}
