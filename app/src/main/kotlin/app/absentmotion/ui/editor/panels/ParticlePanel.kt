package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.truemotion.Particles
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.effects.EffectIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

@Composable
internal fun ParticlePanel(state: EditorState, index: Int, definition: EffectDefinition, modifier: Modifier) {
    val layer = state.selected ?: return
    val instance = layer.effects.getOrNull(index) ?: return
    var tab by rememberSaveable(layer.id, index) { mutableIntStateOf(0) }
    var active by rememberSaveable(layer.id, index) { mutableStateOf("amount") }
    var menu by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val param = definition.params.firstOrNull { it.id == active } ?: definition.params.first()
    val target = param.takeIf { it.kind != "text" }?.let { KeyTarget(PropRef.effect(index, it.id), it.missingDefault, it.type) }
    SideEffect { state.keyTarget = target; state.pointAdjust = null }
    DisposableEffect(layer.id, index) { onDispose { state.endGesture() } }
    if (state.easingOpen) { EasingPanel(state, modifier); return }
    val section = Particles.sections[tab]
    val shown = definition.copy(category = "move", params = definition.params.filter { it.id in section && it.id != "seed" })
    val source = Particles.source(instance)
    val sourceLayer = state.scene.layer(source)
    fun end() { state.playing = false; state.endGesture() }
    fun choose(id: Long) {
        sources = false; end()
        val before = state.scene
        scope.launch {
            val bounds = if (id == 0L) null else state.engine.localBounds(id, state.timeMs)
            if (state.scene != before || state.selectedId != layer.id) return@launch
            val aspect = bounds?.let { if (it[3] > it[1]) (it[2]-it[0])/(it[3]-it[1]) else null }
            state.editSelected { Particles.withSource(it, index, id, aspect) }
        }
    }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            ParticleIcon(PanelIcons.Back, stringResource(R.string.topbar_back)) { end(); state.stepBack() }
            Text(stringResource(R.string.true_particles_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            val keyed = target != null && layer.onKeyframe(target.ref)
            ParticleIcon(if (keyed) PanelIcons.KeyframeRemove else PanelIcons.Keyframe,
                stringResource(if (keyed) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), target != null) { end(); state.toggleKeyframe() }
            ParticleIcon(PanelIcons.Easing, stringResource(R.string.a11y_easing), target != null) {
                end(); if (target != null && layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true
            }
            val visible = instance["hidden"] != "true"
            ParticleIcon(if (visible) EffectIcons.Eye else EffectIcons.EyeOff, stringResource(if (visible) R.string.true_particles_disable else R.string.true_particles_enable)) {
                end(); state.editSelected { it.withEffect(index) { e -> e.with("hidden", if (visible) "true" else null) } }
            }
            Box {
                ParticleIcon(PanelIcons.More, stringResource(R.string.effects_more)) { end(); menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = Am.Field) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.true_particles_reset), color = Am.OnDark) }, onClick = {
                        menu = false; end()
                        val defaults = definition.instance()
                        state.editSelected { current -> current.withEffect(index) { old ->
                            old.copy(children = old.children.filterNot { it.name == "property" && it["name"] in section } +
                                defaults.children.filter { it["name"] in section })
                        } }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = Am.Warning) }, onClick = {
                        menu = false; end(); state.editSelected { it.removeEffect(index) }; state.effectIndex = -1
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.true_particles_emitter, R.string.true_particles_motion, R.string.true_particles_forces, R.string.true_particles_look, R.string.true_particles_camera).forEachIndexed { i, title ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (tab == i) Am.Accent.copy(alpha=.18f) else Am.Field)
                    .clickable { end(); tab=i; active=Particles.sections[i].first() }.padding(horizontal=12.dp, vertical=7.dp)) {
                    Text(stringResource(title), color=if(tab==i) Am.Accent else Am.OnDarkDim, fontSize=12.sp)
                }
            }
        }
        key(tab) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=8.dp)) {
                if (tab == 0) {
                    Text(stringResource(R.string.true_particles_hint), Modifier.padding(horizontal=12.dp, vertical=4.dp), color=Am.OnDarkDim, fontSize=11.sp)
                    val raw = instance.effectProperty("seed")?.get("value") ?: "1"
                    OutlinedTextField(raw, { text ->
                        if (text.isEmpty() || text.toLongOrNull()?.let { it >= 0 } == true) {
                            state.playing=false; active="seed"
                            state.editSelected("particle-seed-$index") { it.withEffect(index) { effect ->
                                effect.copy(children=effect.children.filterNot { p -> p.name=="property" && p["name"]=="seed" } +
                                    XmlNode("property",mapOf("name" to "seed","type" to "string","value" to text)))
                            } }
                        }
                    }, Modifier.fillMaxWidth().padding(horizontal=12.dp), label={Text(definition.params.first { it.id=="seed" }.label)},
                        singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Am.OnDark,unfocusedTextColor=Am.OnDark,focusedBorderColor=Am.Accent,unfocusedBorderColor=Am.Line,focusedLabelColor=Am.Accent,unfocusedLabelColor=Am.OnDarkDim))
                    Text(stringResource(R.string.true_particles_seed_hint),Modifier.padding(12.dp),color=Am.OnDarkDim,fontSize=10.sp)
                }
                if (tab == 3) {
                    Box(Modifier.padding(horizontal=12.dp)) {
                        TextButton({ end(); sources=true },Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.true_particles_source,sourceLayer?.label ?: if(source==0L) stringResource(R.string.true_particles_dot) else "$source"),color=Am.Accent)
                        }
                        DropdownMenu(sources,{sources=false},Modifier.heightIn(max=240.dp),containerColor=Am.Field) {
                            DropdownMenuItem(text={Text(stringResource(R.string.true_particles_dot),color=Am.OnDark)},onClick={choose(0)})
                            state.scene.layers.filter { it.kind !in setOf("audio","camera","nullobj") && it.id!=layer.id }.forEach { other ->
                                DropdownMenuItem(text={Text(other.label,color=Am.OnDark)},enabled=Particles.canSource(state.scene,layer.id,other.id),onClick={choose(other.id)})
                            }
                        }
                    }
                    Text(stringResource(if(source!=0L && sourceLayer==null) R.string.true_particles_source_missing else R.string.true_particles_source_hint),
                        Modifier.padding(horizontal=12.dp,vertical=4.dp),color=if(source!=0L && sourceLayer==null) Am.Warning else Am.OnDarkDim,fontSize=11.sp)
                }
                if(tab==4) Text(stringResource(R.string.true_particles_camera_hint),Modifier.padding(12.dp),color=Am.OnDarkDim,fontSize=11.sp)
                EffectSettings(state,layer,instance,shown,param) { selected -> active=selected.id; state.playing=false }
            }
        }
    }
}

@Composable
private fun ParticleIcon(icon:ImageVector,description:String,enabled:Boolean=true,onClick:()->Unit) {
    Box(Modifier.size(36.dp).clickable(enabled=enabled,onClick=onClick),contentAlignment=Alignment.Center) {
        Icon(icon,description,Modifier.size(22.dp),tint=if(enabled) Am.OnDark else Am.OnDarkDim.copy(alpha=.35f))
    }
}
