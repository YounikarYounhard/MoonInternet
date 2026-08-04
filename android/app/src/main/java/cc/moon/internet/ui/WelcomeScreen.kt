package cc.moon.internet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.R

/**
 * First launch. One screen, not a tour: the only thing the app cannot work without is a
 * subscription, so that is what it asks for — by the two routes that actually exist on a phone,
 * a QR code and the clipboard. Everything else stays out of the way until it is needed.
 */
@Composable
fun WelcomeScreen(onScan: () -> Unit, onPaste: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Moon.WinBg).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.moon_off), null, Modifier.size(110.dp))
        Text("Moon Internet", color = Moon.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.padding(top = 18.dp))
        Text(stringResource(R.string.welcome_sub), color = Moon.TextSecondary, fontSize = 14.sp,
             textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.welcome_body), color = Moon.TextMuted, fontSize = 12.5.sp,
             lineHeight = 18.sp, textAlign = TextAlign.Center,
             modifier = Modifier.padding(top = 20.dp))

        Surface(onClick = onScan, shape = RoundedCornerShape(12.dp), color = Moon.Accent,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
            Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.QrCode2, null, tint = Color.White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.welcome_scan), color = Color.White, fontSize = 14.sp,
                     fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(onClick = onPaste, shape = RoundedCornerShape(12.dp), color = Moon.Card,
                border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentPaste, null, tint = Moon.TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.welcome_paste), color = Moon.TextPrimary, fontSize = 14.sp,
                     fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(onSkip, Modifier.padding(top = 6.dp)) {
            Text(stringResource(R.string.welcome_skip), color = Moon.TextSecondary, fontSize = 13.sp)
        }
    }
}
