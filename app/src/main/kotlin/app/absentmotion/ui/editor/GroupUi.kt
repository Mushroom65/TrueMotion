package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.model.groupScene
import app.absentmotion.ui.theme.Am

@Composable
internal fun GroupBreadcrumb(state: EditorState) {
    if (state.editingGroup) Column {
        Text(
            state.sceneTrail, Modifier.fillMaxWidth().background(Am.PanelInset).padding(horizontal = 12.dp, vertical = 4.dp),
            color = Am.OnDarkDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val linked = state.document.groupScene(state.groupPath.dropLast(1))?.layer(state.groupPath.last())?.link != null
        if (linked) Text(stringResource(R.string.true_group_linked_hint), Modifier.padding(horizontal = 12.dp, vertical = 3.dp), color = Am.OnDarkDim, fontSize = 10.sp)
    }
}

@Composable
internal fun GroupTiming(state: EditorState) {
    val nested = state.selected?.nested ?: return
    val options = listOf(
        "freeze" to R.string.element_retime_freeze,
        "stretch" to R.string.element_retime_stretch,
        "loop" to R.string.element_retime_loop,
        "blank" to R.string.element_retime_blank,
        "off" to R.string.true_group_continue,
    )
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.true_group_timing), color = Am.OnDark, fontSize = 12.sp)
        for (row in options.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((method, label) in row) {
                val active = nested.retime == method
                Text(
                    stringResource(label), Modifier.weight(1f).heightIn(min = 40.dp)
                        .background(if (active) Am.Accent.copy(alpha = .16f) else Am.Button, RoundedCornerShape(6.dp))
                        .clickable {
                            state.playing = false
                            state.editSelected { layer -> layer.copy(node = layer.node.withChild("scene", nested.node.with("retime", method).with("trueTimingExplicit", "true"))) }
                        }.padding(8.dp),
                    color = if (active) Am.Accent else Am.OnDark, fontSize = 11.sp,
                )
            }
        }
        Text(stringResource(R.string.true_group_timing_hint), color = Am.OnDarkDim, fontSize = 10.sp)
    }
}
