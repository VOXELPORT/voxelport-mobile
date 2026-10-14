package com.voxelport.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tip buttons: ₹20 / ₹50 / ₹100 open the UPI app directly, with the amount filled in. */
@Composable
private fun TipButtons(onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (amount in SupportLogic.PRESETS) {
            VpButton("₹$amount", VP.Gold, Modifier.weight(1f)) { tipWithUpi(ctx, amount)?.let(onMessage) }
        }
    }
    VpButton("TIP ANOTHER AMOUNT", VP.Grass, Modifier.fillMaxWidth()) { tipWithUpi(ctx, null)?.let(onMessage) }
}

/** Compact "Support VoxelPort" card on the home screen: one tap opens your UPI app. */
@Composable
fun SupportPanel(onMore: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    Panel {
        PanelTitle("♥ SUPPORT VOXELPORT")
        Text(
            "Free for everyone, no ads. If it helped, a small tip keeps the relay online. Tap an amount to pay with your UPI app.",
            color = VP.Muted, fontSize = 13.sp,
        )
        TipButtons { msg = it }
        if (msg.isNotEmpty()) Text(msg, color = VP.Ink, fontSize = 12.sp)
        TextButton(onMore) { Text("Supporter perks and more ›", color = VP.Ink, fontWeight = FontWeight.Bold) }
    }
}

/** The full Support screen: tips, supporter perks, and (when there are any) paid-hosting suggestions. */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val info by produceState(SupportLogic.DEFAULTS) { value = withContext(Dispatchers.IO) { SupportRepo.load(ctx) } }

    Column(
        Modifier.fillMaxSize().background(VP.Paper).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SUPPORT", fontSize = 30.sp, fontWeight = FontWeight.Black, color = VP.Ink, modifier = Modifier.weight(1f))
            TextButton(onBack) { Text("Done") }
        }

        Panel {
            PanelTitle("♥ TIP JAR")
            Text(
                "VoxelPort has no ads, no accounts and no paid plan. The relay runs on a server I pay for myself. Any amount helps, even ₹20.",
                color = VP.Muted, fontSize = 13.sp,
            )
            TipButtons { msg = it }
            if (msg.isNotEmpty()) Text(msg, color = VP.Ink, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("UPI ID", fontSize = 11.sp, color = VP.Muted)
                    Text(SupportLogic.UPI_ID, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton({ copyUpiId(ctx); copied = true }) { Text(if (copied) "Copied" else "Copy") }
            }
            VpButton("TIP FROM OUTSIDE INDIA", VP.Card, Modifier.fillMaxWidth()) { openLink(ctx, SupportLogic.SPONSORS_URL) }
        }

        val s = info.supporter
        Panel {
            PanelTitle("★ SUPPORTER PERKS")
            Text("Optional", fontSize = 11.sp, color = VP.Muted)
            Text(s.intro, fontSize = 13.sp, color = VP.Ink)
            for (t in s.tiers) {
                Column(Modifier.fillMaxWidth().border(2.dp, VP.Ink).background(VP.Paper).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(t.name.uppercase(), fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("₹${t.inr}+", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    for (p in t.perks) Text("• $p", fontSize = 13.sp, color = VP.Ink)
                }
            }
            Text(s.how, fontSize = 13.sp, color = VP.Ink)
            Text(s.fine, fontSize = 11.sp, color = VP.Muted)
            VpButton("MORE ON VOXELPORT.IN", VP.Card, Modifier.fillMaxWidth()) { openLink(ctx, SupportLogic.PERKS_URL) }
        }

        val h = info.hosts
        if (h.items.isNotEmpty()) Panel {
            PanelTitle("NEED IT ONLINE 24/7?")
            if (h.intro.isNotEmpty()) Text(h.intro, fontSize = 13.sp, color = VP.Ink)
            for (host in h.items) {
                Row(
                    Modifier.fillMaxWidth().border(2.dp, VP.Ink).background(VP.Paper).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(host.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (host.affiliate) Text("affiliate link", fontSize = 10.sp, color = VP.Muted)
                        if (host.blurb.isNotEmpty()) Text(host.blurb, fontSize = 12.sp, color = VP.Ink)
                    }
                    VpButton("VISIT", VP.Gold) { openLink(ctx, host.url) }
                }
            }
            Text(h.disclosure, fontSize = 11.sp, color = VP.Muted)
        }
        Spacer(Modifier.height(24.dp))
    }
}
