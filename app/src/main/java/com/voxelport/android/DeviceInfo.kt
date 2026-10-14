package com.voxelport.android

import android.app.ActivityManager
import android.content.Context

/** RAM (as Android reports it) and CPU core count of this phone. */
fun deviceOf(ctx: Context): Tuning.Device {
    val mi = ActivityManager.MemoryInfo()
    ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
    return Tuning.Device((mi.totalMem / 1_048_576).toInt(), Runtime.getRuntime().availableProcessors())
}
