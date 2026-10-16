package com.voxelport.android

/**
 * The "Copy invite message" text: what a host pastes into WhatsApp or Discord so friends know exactly how to join,
 * including how Bedrock players do it, and where the free hosting came from. Same wording as the desktop app.
 */
object Invite {
    private const val SITE = "https://voxelport.in"

    private fun oneLine(text: String?, max: Int) =
        (text ?: "").replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    /**
     * @param address what to type in Minecraft (the custom address, or host:port)
     * @param plainAddress host:port, shown as the fallback when [address] is a custom name
     * @param bedrockHost / [bedrockPort] set when Bedrock players can join
     */
    fun build(
        lang: String, address: String, version: String? = null, name: String? = null,
        plainAddress: String? = null, bedrockHost: String? = null, bedrockPort: Int = 0,
    ): String {
        val hinglish = lang == Lang.HINGLISH
        val server = oneLine(name, 60)
        val ver = oneLine(version, 20).takeIf { it.isNotEmpty() && it != "unknown" }
        val title = if (hinglish) {
            if (server.isNotEmpty()) "🎮 Mere Minecraft server mein aao: $server" else "🎮 Mere Minecraft server mein aao!"
        } else {
            if (server.isNotEmpty()) "🎮 Join my Minecraft server: $server" else "🎮 Join my Minecraft server!"
        }
        val addr = oneLine(address, 120)
        val plain = oneLine(plainAddress, 120)
        val lines = mutableListOf(title, "", if (ver != null) "Minecraft Java $ver" else "Minecraft Java", "Address: $addr")
        if (plain.isNotEmpty() && plain != addr) lines += "(${if (hinglish) "ya" else "or"}: $plain)"
        if (!bedrockHost.isNullOrBlank() && bedrockPort > 0) {
            lines += listOf("", "📱 Bedrock (phone / console / Windows 10):", "Server: ${oneLine(bedrockHost, 120)}   Port: $bedrockPort")
        }
        lines += ""
        lines += if (hinglish) "Kaise judo: Minecraft → Multiplayer → Add Server → address paste karo." else "How to join: Minecraft → Multiplayer → Add Server → paste the address."
        lines += if (hinglish) "Koi mod nahi chahiye. Free server, VoxelPort se bana hai: $SITE" else "No mods needed. Free server, hosted with VoxelPort: $SITE"
        return lines.joinToString("\n")
    }
}
