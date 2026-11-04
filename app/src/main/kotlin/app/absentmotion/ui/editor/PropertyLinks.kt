package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am

/** Edits only the current property, inside its existing inspector. */
@Composable
internal fun PropertyLinkEditor(state: EditorState, ref: PropRef, modifier: Modifier, close: () -> Unit) {
    val layer=state.selected?:return
    val targets=layer.numericChannels().filter { it.ref==ref }
    if(targets.isEmpty()) return
    var axis by remember(layer.id,ref) { mutableIntStateOf(0) }
    val target=targets.getOrElse(axis) { targets.first() }
    val current=state.scene.resolvedValueLink(layer.id,target)
    val sources=state.scene.layers.filter { it.copySource==null }
    key(layer.id,target) {
        var error by remember { mutableStateOf<String?>(null) }
        var sourceId by remember(current) { mutableStateOf(current?.source?:sources.firstOrNull { it.id!=layer.id }?.id?:layer.id) }
        val source=state.scene.layer(sourceId)?:layer
        val channels=source.numericChannels()
        var channel by remember(sourceId,current) { mutableStateOf(current?.channel?.takeIf { it in channels }?:channels.first()) }
        var multiplier by remember(current) { mutableFloatStateOf(current?.multiply?:1f) }
        var offset by remember(current) { mutableFloatStateOf(current?.offset?:0f) }
        var delay by remember(current) { mutableFloatStateOf((current?.delayMs?:0)*state.scene.fps/1000f) }
        Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.property_link_title)+" · "+channelTitle(target),color=Am.Accent,fontSize=12.sp)
            if(targets.size>1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                targets.forEachIndexed { index,t -> TextButton({axis=index}) { Text(listOf("X","Y","Z","W").getOrElse(t.axis) { "${t.axis+1}" },color=if(t==target)Am.Accent else Am.OnDark) } }
            }
            Text(stringResource(R.string.motion_source),color=Am.OnDarkDim,fontSize=11.sp)
            ChoiceRow(sources,source,{it.label.ifBlank { "#${it.id}" }}) {sourceId=it.id}
            Text(stringResource(R.string.motion_channel),color=Am.OnDarkDim,fontSize=11.sp)
            ChoiceRow(channels,channel,::channelTitle) {channel=it}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                PropertyNumber("×",multiplier,Modifier.weight(1f)) {multiplier=it}
                PropertyNumber("+",offset,Modifier.weight(1f)) {offset=it}
            }
            PropertyNumber(stringResource(R.string.motion_delay),delay) {delay=it}
            Text(stringResource(R.string.motion_link_help),color=Am.OnDarkDim,fontSize=10.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({try {
                    state.edit { it.withValueLink(layer.id,target,ValueLink(source.id,channel,multiplier,offset,(delay*1000/state.scene.fps).toInt())) };close()
                } catch(e:IllegalArgumentException) {error=e.message} }) {Text(stringResource(R.string.motion_link_apply),color=Am.Accent)}
                if(current!=null) TextButton({state.edit {it.withoutValueLink(layer.id,target)};close()}) {Text(stringResource(R.string.property_unlink),color=Am.Accent)}
                TextButton(close) {Text(stringResource(R.string.done),color=Am.OnDarkDim)}
            }
            error?.let {Text(it,color=Am.Warning,fontSize=11.sp)}
        }
    }
}

@Composable
private fun <T> ChoiceRow(values:List<T>,selected:T,title:(T)->String,choose:(T)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { values.forEach { value ->
        TextButton({choose(value)},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {Text(title(value),color=if(value==selected)Am.Accent else Am.OnDark,fontSize=11.sp,maxLines=1)}
    } }
}

internal fun Layer.hasValueLink(ref:PropRef):Boolean=element(ref)?.children?.any {it.name=="trueValueLink"}==true

private fun channelTitle(channel:ValueChannel):String {
    val name=when(channel.ref) {
        PropRef.Location -> app.absentmotion.i18n.AppText.get(R.string.true_position)
        PropRef.Rotation -> app.absentmotion.i18n.AppText.get(R.string.true_rotation)
        PropRef.Scale -> app.absentmotion.i18n.AppText.get(R.string.true_scale)
        PropRef.Opacity -> app.absentmotion.i18n.AppText.get(R.string.true_opacity)
        else -> channel.ref.name
    }
    val axis=if(channel.ref in listOf(PropRef.Location,PropRef.Scale,PropRef.Pivot,PropRef.Tilt,PropRef.Skew)) " "+listOf("X","Y","Z").getOrElse(channel.axis) {"${channel.axis+1}"} else if(channel.axis>0) " ${channel.axis+1}" else ""
    return (if(channel.ref.kind==PropRef.Kind.Effect) "FX ${channel.ref.effectIndex+1} · " else "")+name+axis
}

/** Repetition belongs to the existing effect list. Position/scale/opacity use normal layer controls. */
@Composable
internal fun RepeatedCopiesControls(state:EditorState,layer:Layer) {
    val sourceId=layer.copySource?:layer.id
    val copies=state.scene.repeatedCopies(sourceId)
    var expanded by remember(layer.id) {mutableStateOf(copies.isNotEmpty()||layer.copySource!=null)}
    var error by remember(layer.id) {mutableStateOf<String?>(null)}
    fun edit(change:(Scene)->Scene) {try {state.edit(change=change);error=null} catch(e:IllegalArgumentException) {error=e.message}}
    Column(Modifier.fillMaxWidth().padding(6.dp).background(Am.Field)) {
        Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable {expanded=!expanded}.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text((if(expanded)"▾ " else "▸ ")+stringResource(R.string.motion_copies),Modifier.weight(1f),color=Am.OnDark,fontSize=13.sp)
            Text("${copies.size+1}",color=Am.Accent,fontSize=12.sp)
        }
        if(expanded) Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.repeat_controls_help),color=Am.OnDarkDim,fontSize=11.sp)
            if(layer.copySource==null) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    PropertyNumber(stringResource(R.string.motion_count),(copies.size+1).toFloat(),Modifier.weight(1f),commitOnBlur=true) {n->if(n.toInt() in 1..200)edit {it.withRepeatedCopies(sourceId,n.toInt())}}
                    TextButton({edit {it.withRepeatedCopies(sourceId,copies.size+2)}},enabled=copies.size<199) {Text("+",color=Am.Accent)}
                }
            } else {
                TextButton({state.selectedId=sourceId}) {Text(stringResource(R.string.motion_go_original),color=Am.Accent)}
                val offset=layer.copyOffset!!
                PropertyNumber(stringResource(R.string.motion_delay),offset.delayFrames) {v->edit {it.withCopyOffset(layer.id,offset.copy(delayFrames=v))}}
                TextButton({edit {it.detachRepeatedCopy(layer.id)}}) {Text(stringResource(R.string.motion_detach),color=Am.Accent)}
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(sourceId)+copies.map {it.id}).forEachIndexed {index,id->TextButton({state.selectedId=id}) {Text("$index",color=if(id==layer.id)Am.Accent else Am.OnDark)}}
            }
            error?.let {Text(it,color=Am.Warning,fontSize=11.sp)}
        }
    }
}

@Composable
internal fun SceneLightControls(state:EditorState,onSelect:()->Unit={}) {
    Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.depth_light),color=Am.Accent,fontSize=12.sp)
        listOf("trueLightAzimuth" to (R.string.depth_azimuth to 135f),"trueLightElevation" to (R.string.depth_elevation to 50f),
            "trueLightIntensity" to (R.string.model_param_light_intensity to 1f),"trueLightAmbient" to (R.string.model_param_ambient_light to .35f)).forEach {(name,definition)->
            PropertyNumber(stringResource(definition.first),state.scene.node[name]?.toFloatOrNull()?:definition.second,onSelect=onSelect) {v->
                onSelect();val value=when(name) {"trueLightIntensity"->v.coerceIn(0f,4f);"trueLightAmbient"->v.coerceIn(0f,1f);else->v}
                state.edit {Scene(it.node.with(name,"$value"))}
            }
        }
    }
}

@Composable
internal fun PropertyNumber(label:String,value:Float,modifier:Modifier=Modifier,commitOnBlur:Boolean=false,onSelect:()->Unit={},change:(Float)->Unit) {
    var text by remember {mutableStateOf(value.toString())}
    var focused by remember {mutableStateOf(false)}
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(value,focused) {if(!focused)text=value.toString()}
    OutlinedTextField(text,{text=it;if(!commitOnBlur)it.toFloatOrNull()?.takeIf(Float::isFinite)?.let(change)},
        modifier=modifier.fillMaxWidth().onFocusChanged {
            val lost=focused&&!it.isFocused;focused=it.isFocused
            if(it.isFocused)onSelect()
            if(lost&&commitOnBlur)text.toFloatOrNull()?.takeIf(Float::isFinite)?.let(change)
        },
        label={Text(label,fontSize=11.sp)},singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(fontSize=13.sp),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={focus.clearFocus()}),
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Am.OnDark,unfocusedTextColor=Am.OnDark,focusedBorderColor=Am.Accent,unfocusedBorderColor=Am.OnDarkDim))
}
