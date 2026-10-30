package app.absentmotion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ui.theme.Am

@Composable
internal fun TrueBrandLogo(size: Dp) {
    Image(painterResource(R.drawable.true_motion_logo), null, Modifier.size(size))
}

@Composable
internal fun TrueBrandTitle(modifier: Modifier = Modifier) {
    Row(modifier, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        TrueBrandLogo(32.dp)
        Text("True Motion Next", color = Am.Navy, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
