package app.absentmotion.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ui.theme.Am

@Composable
internal fun PackageFormat(alight: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
        Text(stringResource(R.string.true_package_format), Modifier.weight(1f), color = Am.Navy, fontSize = 13.sp)
        listOf(true to ".alight", false to ".absproj").forEach { (value, name) ->
            val selected = alight == value
            Text(name, Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) Am.Accent else Am.Field)
                .clickable { change(value) }.padding(horizontal = 12.dp, vertical = 10.dp), color = if (selected) Am.OnAccent else Am.Navy, fontSize = 13.sp)
        }
    }
}
