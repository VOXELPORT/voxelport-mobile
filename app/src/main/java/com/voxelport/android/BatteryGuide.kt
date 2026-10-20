package com.voxelport.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Phone makers whose battery managers kill background apps beyond what stock Android does. */
enum class Oem { SAMSUNG, XIAOMI, OPPO, VIVO, HUAWEI, OTHER }

/**
 * Pure part of the "keep your server running" help: which maker a phone is, and which of that maker's
 * settings screens to try opening. Makers move these screens between versions, so every candidate is tried
 * in turn and the app's own settings page is the last resort.
 */
object BatteryGuide {
    fun detect(manufacturer: String, brand: String): Oem {
        val s = (manufacturer + " " + brand).lowercase()
        return when {
            "samsung" in s -> Oem.SAMSUNG
            "xiaomi" in s || "redmi" in s || "poco" in s -> Oem.XIAOMI
            "oppo" in s || "realme" in s || "oneplus" in s -> Oem.OPPO
            "vivo" in s || "iqoo" in s -> Oem.VIVO
            "huawei" in s || "honor" in s -> Oem.HUAWEI
            else -> Oem.OTHER
        }
    }

    /** (package, activity) pairs to try, best first. */
    fun screens(oem: Oem): List<Pair<String, String>> = when (oem) {
        Oem.SAMSUNG -> listOf("com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity")
        Oem.XIAOMI -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
        Oem.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        )
        Oem.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        )
        Oem.HUAWEI -> listOf("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        Oem.OTHER -> emptyList()
    }

    /** Opens the maker's screen if this phone has it, otherwise VoxelPort's own app settings. */
    fun open(ctx: Context, oem: Oem) {
        for ((pkg, cls) in screens(oem)) {
            val ok = runCatching {
                ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Shown while the server runs: what to switch on so this phone's battery manager leaves VoxelPort alone. */
@Composable
fun BatteryGuideCard(oem: Oem, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("KEEP IT RUNNING WITH THE SCREEN OFF")
        Text("Some phones stop apps in the background to save battery, which would take your server offline.", color = VP.Muted, fontSize = 13.sp)
        when (oem) {
            Oem.SAMSUNG -> {
                Text("1. Settings → Battery → Background usage limits.", fontSize = 13.sp)
                Text("2. Turn off \"Put unused apps to sleep\", or add VoxelPort to \"Never sleeping apps\".", fontSize = 13.sp)
            }
            Oem.XIAOMI -> {
                Text("1. Turn on Autostart for VoxelPort.", fontSize = 13.sp)
                Text("2. App info → Battery saver → No restrictions.", fontSize = 13.sp)
            }
            Oem.OPPO -> {
                Text("1. Turn on Auto launch for VoxelPort.", fontSize = 13.sp)
                Text("2. App info → Battery usage → Allow background activity.", fontSize = 13.sp)
            }
            Oem.VIVO -> {
                Text("1. Turn on Autostart for VoxelPort.", fontSize = 13.sp)
                Text("2. Settings → Battery → Background power consumption → VoxelPort → Allow.", fontSize = 13.sp)
            }
            Oem.HUAWEI -> {
                Text("1. Settings → Battery → App launch → VoxelPort → Manage manually.", fontSize = 13.sp)
                Text("2. Switch on Auto-launch, Secondary launch and Run in background.", fontSize = 13.sp)
            }
            Oem.OTHER -> {
                Text("1. App info → Battery → Unrestricted (or \"Don't optimise\").", fontSize = 13.sp)
            }
        }
        Text("Also lock VoxelPort in the recent apps list (long-press its card → Lock) so swiping apps away doesn't close it.", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VpButton("OPEN SETTINGS", VP.Grass, Modifier.weight(1f)) { onOpen() }
            VpButton("GOT IT", VP.Card, Modifier.weight(1f)) { onDismiss() }
        }
    }
}
