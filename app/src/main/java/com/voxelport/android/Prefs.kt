package com.voxelport.android

import android.content.Context
import com.voxelport.go.mobile.Mobile

/** Small settings, plus this install's VoxelPort identity (its public port and address belong to it). */
class Prefs(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("voxelport", Context.MODE_PRIVATE)

    val token: String
        get() {
            p.getString("token", null)?.takeIf { Mobile.validToken(it) }?.let { return it }
            val t = Mobile.newToken()
            p.edit().putString("token", t).apply()
            return t
        }

    var claimName: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()

    /** Server heap in MB; until the user picks one, a size that suits this phone. */
    var ramMb: Int
        get() = if (p.contains("ram")) p.getInt("ram", 2048) else Tuning.recommendedRamMb(deviceOf(ctx))
        set(v) = p.edit().putInt("ram", v).apply()

    var bedrock: Boolean
        get() = p.getBoolean("bedrock", true)
        set(v) = p.edit().putBoolean("bedrock", v).apply()

    var eula: Boolean
        get() = p.getBoolean("eula", false)
        set(v) = p.edit().putBoolean("eula", v).apply()

    /** When we last asked voxelport.in about an app update (ms since epoch). */
    var lastUpdateCheck: Long
        get() = p.getLong("update_check", 0L)
        set(v) = p.edit().putLong("update_check", v).apply()

    /** Performance level: "auto" (from the phone), "low", "balanced" or "high". */
    var profile: String
        get() = p.getString("profile", "auto") ?: "auto"
        set(v) = p.edit().putString("profile", v).apply()

    /** Server software to install: "paper" (plugins) or "fabric" (mods). */
    var type: String
        get() = p.getString("type", "paper") ?: "paper"
        set(v) = p.edit().putString("type", v).apply()

    /** Minecraft/Paper version to install; empty means "latest". */
    var version: String
        get() = p.getString("version", "") ?: ""
        set(v) = p.edit().putString("version", v).apply()
}
