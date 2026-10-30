package com.voxelport.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Why this person is not allowed to host publicly yet, in words. */
private fun whyText(reason: String): String = when (reason) {
    "not_in_server" -> t("You're not in the VoxelPort Discord server yet.")
    "screening" -> t("Finish the server's rules screening, then check again.")
    "session_rejected", "recheck" -> t("Please sign in with Discord again.")
    else -> ""
}

/** What moves this person forward from where they are: sign in, or join the server and check again. */
@Composable
private fun DiscordActions(acct: AccountState, onSignIn: () -> Unit, onCancel: () -> Unit, onInvite: () -> Unit, onRecheck: () -> Unit) {
    when {
        acct.signingIn -> {
            Text("Finish signing in in your browser…", color = VP.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            VpButton("Cancel", VP.Card, Modifier.fillMaxWidth(), onClick = onCancel)
        }
        acct.gate.mustSignIn -> VpButton("Continue with Discord", VP.Diamond, Modifier.fillMaxWidth(), onClick = onSignIn)
        else -> {
            VpButton("Join the Discord server", VP.Diamond, Modifier.fillMaxWidth(), onClick = onInvite)
            VpButton("Check again", VP.Card, Modifier.fillMaxWidth(), onClick = onRecheck)
        }
    }
}

/**
 * The full-page sign-in. It covers the app for everyone who is not allowed to host, whatever the age of the install, and
 * only once the accounts service has answered (if it is down there is nothing to sign in to, so a small banner is
 * shown instead).
 */
@Composable
fun DiscordGateScreen(acct: AccountState, onSignIn: () -> Unit, onCancel: () -> Unit, onInvite: () -> Unit, onRecheck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VP.Paper).systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Image(ImageBitmap.imageResource(R.drawable.vp_logo), null, Modifier.size(72.dp), filterQuality = FilterQuality.None)
        Text("VOXELPORT", fontSize = 28.sp, fontWeight = FontWeight.Black, color = VP.Ink)
        Text("Join the VoxelPort Discord to continue", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = VP.Ink, textAlign = TextAlign.Center)
        Text(
            "Everyone who uses VoxelPort joins our Discord server. It's where we share updates, giveaways and help.",
            color = VP.Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        val why = whyText(acct.gate.reason)
        if (why.isNotEmpty()) Text(why, color = VP.Ink, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (acct.signInFailed) Text("Sign-in didn't finish. Try again.", color = VP.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        DiscordActions(acct, onSignIn, onCancel, onInvite, onRecheck)
    }
}

/**
 * A small banner on the home screen for the cases the full page does not cover: the grace period (if the owner sets
 * one) and a service that cannot be reached right now.
 */
@Composable
fun DiscordBanner(acct: AccountState, onSignIn: () -> Unit, onCancel: () -> Unit, onInvite: () -> Unit, onRecheck: () -> Unit) {
    var dismissed by remember { mutableStateOf(false) }
    val grace = acct.gate.banner == GateLogic.Banner.GRACE && !dismissed
    val blocked = !acct.gate.allowed && !acct.reachable
    if (!(grace || blocked)) return
    val left = acct.gate.graceDaysLeft
    Panel {
        Text(
            if (acct.gate.banner == GateLogic.Banner.GRACE && left != null) t("Join the VoxelPort Discord within {n} days to keep hosting.", "n" to left)
            else t("Join the VoxelPort Discord to host your server publicly."),
            fontWeight = FontWeight.Bold,
        )
        if (acct.signInFailed) Text("Sign-in didn't finish. Try again.", color = VP.Red, fontSize = 13.sp)
        DiscordActions(acct, onSignIn, onCancel, onInvite, onRecheck)
        if (grace) TextButton({ dismissed = true }) { Text("Not now") }
    }
}

/** The account row: who is signed in, the rank it earns on the relays, and a button to sign in or out. */
@Composable
fun DiscordPanel(acct: AccountState, onSignIn: () -> Unit, onCancel: () -> Unit, onSignOut: () -> Unit) {
    Panel {
        PanelTitle("DISCORD ACCOUNT")
        val name = acct.discordName
        Text(if (acct.signedIn && name != null) t("Signed in as {name}", "name" to name) else t("Not signed in with Discord."), fontWeight = FontWeight.Bold)
        if (acct.signedIn && name != null && acct.rank != null) {
            Text(t("Your rank: {rank}", "rank" to AccountLogic.rankText(acct.rank, acct.planName)), color = VP.Muted, fontSize = 14.sp)
        }
        acct.linkError?.let { Text(it, color = VP.Muted, fontSize = 12.sp) }
        when {
            acct.signedIn -> VpButton("Sign out", VP.Card, Modifier.fillMaxWidth(), onClick = onSignOut)
            acct.signingIn -> VpButton("Cancel", VP.Card, Modifier.fillMaxWidth(), onClick = onCancel)
            else -> VpButton("Continue with Discord", VP.Diamond, Modifier.fillMaxWidth(), onClick = onSignIn)
        }
    }
}
