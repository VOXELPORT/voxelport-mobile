package com.voxelport.android

/** Native bridge to the Java runtime launcher (src/main/cpp/launcher.c). Used only in the :jvm process. */
object Jvm {
    init {
        System.loadLibrary("vplauncher")
    }

    /** Runs Java with [args] from [home], in [cwd], logging to [log]. Blocks until the server stops. */
    @JvmStatic external fun launch(home: String, cwd: String, args: Array<String>, log: String): Int

    /** Writes one console command line to the server's stdin. */
    @JvmStatic external fun command(line: String): Boolean
}
