package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeLogicTest {
    @Test fun `chat text is one plain line without formatting codes or controls`() {
        assertEquals("Hi cred line", NoticeLogic.chatSafe("Hi§cred\nline"))
        assertEquals("spaced out", NoticeLogic.chatSafe("  spaced   out  "))
        assertEquals("", NoticeLogic.chatSafe(null))
        assertEquals(NoticeLogic.MAX_CHAT, NoticeLogic.chatSafe("a".repeat(500)).length)
    }

    @Test fun `the time left is said in plain words`() {
        assertEquals("now", NoticeLogic.timeLeftPhrase(0))
        assertEquals("in less than a minute", NoticeLogic.timeLeftPhrase(30))
        assertEquals("in 1 minute", NoticeLogic.timeLeftPhrase(60))
        assertEquals("in 2 minutes", NoticeLogic.timeLeftPhrase(61))
        assertEquals("in 10 minutes", NoticeLogic.timeLeftPhrase(600))
        assertEquals("in about 3 hours", NoticeLogic.timeLeftPhrase(3 * 3600))
    }

    @Test fun `a timed notice is said at once again 5 and 1 minutes before and when due`() {
        val plan = NoticeLogic.plan("The VoxelPort relay will restart.", 600, repeat = false)
        assertEquals(
            listOf(
                0 to "[VoxelPort] The VoxelPort relay will restart. (in 10 minutes)",
                300 to "[VoxelPort] The VoxelPort relay will restart. (in 5 minutes)",
                540 to "[VoxelPort] The VoxelPort relay will restart. (in 1 minute)",
                600 to "[VoxelPort] The VoxelPort relay will restart. (now)",
            ),
            plan,
        )
    }

    @Test fun `a short notice only gets the reminders that still fit`() {
        val plan = NoticeLogic.plan("Restarting.", 100, repeat = false)
        assertEquals(listOf(0, 40, 100), plan.map { it.first })
        assertTrue(plan.map { it.second.substringAfterLast('(') }.containsAll(listOf("in 2 minutes)", "in 1 minute)", "now)")))
    }

    @Test fun `a notice with no time is said once with no time in it`() {
        assertEquals(listOf(0 to "[VoxelPort] Maintenance tonight."), NoticeLogic.plan("Maintenance tonight.", 0, repeat = false))
    }

    @Test fun `the same notice handed over again is not said at once, but the countdown stays right`() {
        val plan = NoticeLogic.plan("The VoxelPort relay will restart.", 500, repeat = true)
        assertFalse("not spoken twice", plan.any { it.first == 0 })
        assertEquals(listOf(200, 440, 500), plan.map { it.first })
    }

    @Test fun `an empty notice has nothing to say`() {
        assertTrue(NoticeLogic.plan("", 600, repeat = false).isEmpty())
        assertTrue(NoticeLogic.plan("   ", 600, repeat = false).isEmpty())
    }
}

class InviteTest {
    private val full = {
        Invite.build("fastayu-paid01.voxelport.in", version = "1.21.11", name = "Survival SMP", plainAddress = "play.voxelport.in:26043", bedrockHost = "fastayu-paid01.voxelport.in", bedrockPort = 26043)
    }

    @Test fun `the message has everything a friend needs in order`() {
        assertEquals(
            listOf(
                "🎮 Join my Minecraft server: Survival SMP", "", "Minecraft Java 1.21.11", "Address: fastayu-paid01.voxelport.in", "(or: play.voxelport.in:26043)", "",
                "📱 Bedrock (phone / console / Windows 10):", "Server: fastayu-paid01.voxelport.in   Port: 26043", "",
                "How to join: Minecraft → Multiplayer → Add Server → paste the address.", "No mods needed. Free server, hosted with VoxelPort: https://voxelport.in",
            ).joinToString("\n"),
            full(),
        )
    }

    @Test fun `without a custom address there is one address and no or line`() {
        val m = Invite.build("play.voxelport.in:26137", version = "1.21.11", plainAddress = "play.voxelport.in:26137")
        assertTrue(m.contains("Address: play.voxelport.in:26137"))
        assertFalse(m.contains("(or:"))
        assertTrue(m.startsWith("🎮 Join my Minecraft server!\n"))
    }

    @Test fun `bedrock lines only appear when bedrock is on`() {
        val m = Invite.build("a.voxelport.in", version = "1.21.11")
        assertFalse(m.contains("Bedrock"))
        assertFalse(m.contains("Server: "))
    }

    @Test fun `an unknown version is left out`() {
        assertTrue(Invite.build("a.voxelport.in", version = "unknown").contains("\nMinecraft Java\n"))
        assertTrue(Invite.build("a.voxelport.in", version = "").contains("\nMinecraft Java\n"))
    }

    @Test fun `line breaks and control characters cannot split the message`() {
        val m = Invite.build("x.voxelport.in\nAnd more", name = "Evil\n\nSend money to me\u0007")
        assertTrue(m.startsWith("🎮 Join my Minecraft server: Evil Send money to me\n"))
        assertTrue(m.contains("Address: x.voxelport.in And more"))
        assertFalse(m.contains('\u0007'))
    }

    @Test fun `overlong names and addresses are cut`() {
        assertTrue(Invite.build("a".repeat(500), name = "N".repeat(500)).length < 900)
    }

    @Test fun `every version credits VoxelPort with a link`() {
    }
}
