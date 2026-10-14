package com.voxelport.android

import android.content.Context
import com.voxelport.go.mobile.Mobile

/** Small settings, plus this install's VoxelPort identity (its public port and address belong to it). */
class Prefs(ctx: Context) {
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

    var ramMb: Int
        get() = p.getInt("ram", 2048)
        set(v) = p.edit().putInt("ram", v).apply()

    var bedrock: Boolean
        get() = p.getBoolean("bedrock", true)
        set(v) = p.edit().putBoolean("bedrock", v).apply()

    var eula: Boolean
        get() = p.getBoolean("eula", false)
        set(v) = p.edit().putBoolean("eula", v).apply()

    /** Server software to install: "paper" (plugins) or "fabric" (mods). */
    var type: String
        get() = p.getString("type", "paper") ?: "paper"
        set(v) = p.edit().putString("type", v).apply()

    /** Minecraft/Paper version to install; empty means "latest". */
    var version: String
        get() = p.getString("version", "") ?: ""
        set(v) = p.edit().putString("version", v).apply()
}
