package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.*
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.truemotion.Spectrum
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am

@Composable
internal fun SpectrumPanel(state:EditorState,index:Int,definition:EffectDefinition,modifier:Modifier) {
    val layer=state.selected ?: return
    val instance=layer.effects.getOrNull(index) ?: return
    var tab by rememberSaveable(layer.id,index) { mutableIntStateOf(0) }
    var active by rememberSaveable(layer.id,index) { mutableStateOf("style") }
    var menu by remember { mutableStateOf(false) }
    val analysis by state.engine.audioEffects.collectAsState()
    val parameter=definition.params.firstOrNull { it.id==active } ?: definition.params.first()
    SideEffect {
        state.keyTarget=if(parameter.kind=="text" || active in setOf("respectVolume","attackMs","releaseMs")) null
            else KeyTarget(PropRef.effect(index,active),parameter.default,parameter.type)
        state.pointAdjust=null
    }
    DisposableEffect(layer.id,index) { onDispose { state.endGesture() } }
    if(state.easingOpen) { EasingPanel(state,modifier);return }
    val audio=setOf("gain","gate","normalize","respectVolume","attackMs","releaseMs","minHz","maxHz","floorDb","offsetMs")
    val colour=setOf("colorLow","colorHigh","glow")
    val shown=definition.copy(params=definition.params.filter { p -> p.id!="sourceLayer" && when(tab) {
        1 -> p.id in colour
        2 -> p.id in audio
        else -> p.id !in colour && p.id !in audio
    } })
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            IconButton({ state.effectIndex=-1 }) { Icon(PanelIcons.Back,stringResource(R.string.topbar_back),tint=Am.OnDark) }
            Text(definition.name,Modifier.weight(1f).padding(12.dp),color=Am.OnDark,fontSize=15.sp)
            IconButton({ state.endGesture();state.toggleKeyframe() },enabled=state.keyTarget!=null) {
                Icon(PanelIcons.Keyframe,stringResource(R.string.a11y_add_keyframe),tint=Am.OnDark)
            }
            IconButton({ state.endGesture();state.easingOpen=true },enabled=state.keyTarget?.let { layer.easing(it.ref)!=null }==true) {
                Icon(PanelIcons.Easing,stringResource(R.string.a11y_easing),tint=Am.OnDark)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(R.string.true_spectrum_shape,R.string.true_spectrum_color,R.string.true_spectrum_audio).forEachIndexed { i,title ->
                Text(stringResource(title),Modifier.clip(RoundedCornerShape(8.dp)).background(if(tab==i) Am.Accent.copy(alpha=.18f) else Am.Field)
                    .clickable { state.playing=false;state.endGesture();tab=i }.padding(horizontal=14.dp,vertical=8.dp),
                    color=if(tab==i) Am.Accent else Am.OnDarkDim,fontSize=12.sp)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if(analysis.pending) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Am.Accent)
            analysis.error?.let { Text(it,Modifier.padding(12.dp),color=Am.Warning,fontSize=11.sp) }
            Box {
                val source=Spectrum.config(instance).source
                TextButton({ menu=true },Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.true_spectrum_source,source?.let { state.scene.layer(it)?.label ?: "$it" } ?: stringResource(R.string.true_spectrum_mix)),color=Am.Accent)
                }
                DropdownMenu(menu,{menu=false},containerColor=Am.Field,modifier=Modifier.heightIn(max=280.dp)) {
                    fun choose(id:Long?) {
                        menu=false;state.playing=false;state.endGesture()
                        state.editSelected { it.withEffect(index) { e -> e.withEffectProperty("sourceLayer","string","${id ?: 0}",0f,0f..0f) } }
                    }
                    DropdownMenuItem(text={Text(stringResource(R.string.true_spectrum_mix),color=Am.OnDark)},onClick={choose(null)})
                    state.scene.layers.filter { it.soundUri!=null }.forEach { source ->
                        DropdownMenuItem(text={Text(source.label,color=Am.OnDark)},onClick={choose(source.id)})
                    }
                }
            }
            if(tab==2)Text(stringResource(R.string.true_spectrum_hint),Modifier.padding(12.dp),color=Am.OnDarkDim,fontSize=11.sp)
            EffectSettings(state,layer,instance,shown,parameter) { selected -> active=selected.id;state.playing=false }
        }
    }
}
