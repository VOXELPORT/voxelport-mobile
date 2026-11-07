package com.voxelport.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The Support screen's rules: where tips go (fixed here, so a changed website can never redirect
 * them), and how the wording, supporter perks and paid-hosting suggestions read from
 * https://voxelport.in/support.json are validated. Plain logic (no Android calls) so it is unit-tested.
 */
object SupportLogic {
    const val SUPPORT_URL = "https://voxelport.in/support.json"
    const val UPI_ID = "trazhub@upi"
    const val SPONSORS_URL = "https://github.com/sponsors/trazhub"
    const val PERKS_URL = "https://voxelport.in/support#supporters"
    const val CONTACT = "traz@voxelport.in"
    /** One-tap tip amounts in ₹ (a plain "any amount" button sits beside them). */
    val PRESETS = listOf(20, 50, 100)

    private const val MAX_HOSTS = 6
    private const val MAX_TIERS = 4
    private const val MAX_PERKS = 8
    const val MAX_JSON_CHARS = 64 * 1024

    /** The UPI payment link; amount is optional (the payer can always change it in their UPI app). */
    fun upiUri(amountInr: Int? = null): String =
        "upi://pay?pa=$UPI_ID&pn=VoxelPort&cu=INR&tn=Support%20VoxelPort" + (amountInr?.takeIf { it in 1..100_000 }?.let { "&am=$it.00" } ?: "")

    data class Tier(val name: String, val inr: Int, val perks: List<String>)
    data class Supporter(val intro: String, val tiers: List<Tier>, val how: String, val fine: String)
    data class Host(val id: String, val name: String, val blurb: String, val url: String, val affiliate: Boolean)
    data class Hosts(val intro: String, val disclosure: String, val items: List<Host>)
    data class Info(val supporter: Supporter, val hosts: Hosts)

    // Kept word for word the same as website/src/support.js, which is what the app shows once it has loaded support.json.
    val DEFAULT_SUPPORTER = Supporter(
        intro = "VoxelPort hosting stays free for everyone: the app, the free address (like play.voxelport.in:26137) and the public server list. A custom name like yourname.voxelport.in is a supporter perk. If you want to chip in, supporters also get a few extras as a thank-you.",
        tiers = listOf(
            Tier("Supporter", 99, listOf(
                "★ Supporter badge on the server list, and your server pinned to the top",
                "A custom address like yourname.voxelport.in, so Java players skip the port number (new custom names are for supporters, and yours never expires from disuse)",
            )),
            Tier("Supporter+", 299, listOf(
                "Everything in Supporter",
                "A short address of 1–2 characters, like vp.voxelport.in (first come, first served; you pick it)",
                "Automatic voice chat on Paper and Fabric servers: once your rank is on, the app installs and sets up Simple Voice Chat for you and friends just join with your server address (they need the matching voice mod)",
            )),
        ),
        how = "Tip any amount (₹99 or more for Supporter, ₹299 or more for Supporter+) by UPI, then email traz@voxelport.in with your UPI reference number, your server's address (like mysmp.voxelport.in) and, for Supporter+, your VoxelPort ID (copy it from the app's server settings). It's switched on by hand, usually within a day or two.",
        fine = "Perks are a thank-you for a donation, not a purchase, and last as long as VoxelPort runs. If one is ever misused it can be taken back.",
    )
    const val DEFAULT_DISCLOSURE =
        "Some of these are affiliate links: VoxelPort may earn a commission if you sign up, at no extra cost to you. VoxelPort itself stays free either way."
    val NO_HOSTS = Hosts("", "", emptyList())
    val DEFAULTS = Info(DEFAULT_SUPPORTER, NO_HOSTS)

    /** Plain text: control characters and angle brackets removed, whitespace collapsed, cut to [max]. */
    fun clean(value: String?, max: Int): String {
        if (value == null) return ""
        val s = value.replace(Regex("[\\u0000-\\u001f\\u007f<>]"), " ").replace(Regex("\\s+"), " ").trim()
        return if (s.length > max) s.take(max).trim() else s
    }

    /** A hosting link is https to a real domain: no credentials, no port, no IP address, no localhost. */
    fun isSafeHostUrl(value: String?): Boolean {
        if (value == null || value.length > 500) return false
        val u = try { URI(value) } catch (_: Exception) { return false }
        if (u.scheme != "https" || u.userInfo != null || u.port != -1) return false
        val h = (u.host ?: return false).lowercase()
        if (!h.contains('.') || h == "localhost" || h.endsWith(".local") || h.endsWith(".internal")) return false
        if (h.contains(':') || Regex("^[\\d.]+$").matches(h)) return false
        return true
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) opt(key) as? String else null

    /** Hosting suggestions for [device] ("desktop" or "android"); no valid entries means "show nothing". */
    fun parseHosts(raw: JSONObject?, device: String): Hosts {
        raw ?: return NO_HOSTS
        val arr = raw.optJSONArray("items") ?: return NO_HOSTS
        val items = ArrayList<Host>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            if (items.size >= MAX_HOSTS) break
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("id") ?: continue
            if (!Regex("^[a-z0-9-]{1,32}$").matches(id) || !seen.add(id)) continue
            val name = clean(o.str("name"), 40)
            val url = o.str("url")
            if (name.isEmpty() || !isSafeHostUrl(url)) continue
            val forDevices = o.optJSONArray("for")
            if (forDevices != null && (0 until forDevices.length()).none { forDevices.optString(it) == device }) continue
            items += Host(id, name, clean(o.str("blurb"), 140), url!!, o.optBoolean("affiliate", false))
        }
        if (items.isEmpty()) return NO_HOSTS
        val introRaw = raw.opt("intro")
        val intro = when (introRaw) {
            is JSONObject -> clean(introRaw.str(device), 220)
            is String -> clean(introRaw, 220)
            else -> ""
        }
        return Hosts(intro, clean(raw.str("disclosure"), 300).ifEmpty { DEFAULT_DISCLOSURE }, items)
    }

    fun parseSupporter(raw: JSONObject?): Supporter {
        raw ?: return DEFAULT_SUPPORTER
        val arr = raw.optJSONArray("tiers") ?: return DEFAULT_SUPPORTER
        val tiers = ArrayList<Tier>()
        for (i in 0 until minOf(arr.length(), MAX_TIERS)) {
            val t = arr.optJSONObject(i) ?: continue
            val name = clean(t.str("name"), 30)
            val inr = t.optDouble("inr", 0.0).takeIf { it > 0 && it < 1e6 }?.let { Math.round(it).toInt() } ?: 0
            val perksArr: JSONArray = t.optJSONArray("perks") ?: JSONArray()
            val perks = (0 until minOf(perksArr.length(), MAX_PERKS)).map { clean(perksArr.optString(it), 200) }.filter { it.isNotEmpty() }
            if (name.isNotEmpty() && inr > 0 && perks.isNotEmpty()) tiers += Tier(name, inr, perks)
        }
        if (tiers.isEmpty()) return DEFAULT_SUPPORTER
        return Supporter(
            intro = clean(raw.str("intro"), 300).ifEmpty { DEFAULT_SUPPORTER.intro },
            tiers = tiers,
            how = clean(raw.str("how"), 400).ifEmpty { DEFAULT_SUPPORTER.how },
            fine = clean(raw.str("fine"), 300).ifEmpty { DEFAULT_SUPPORTER.fine },
        )
    }

    /** Parses a support.json body; anything unexpected gives the built-in defaults. Never throws. */
    fun parse(text: String?, device: String = "android"): Info {
        if (text == null || text.length > MAX_JSON_CHARS) return DEFAULTS
        return try {
            val o = JSONObject(text)
            if (o.optInt("version") != 1) DEFAULTS
            else Info(parseSupporter(o.optJSONObject("supporter")), parseHosts(o.optJSONObject("hosts"), device))
        } catch (_: Exception) { DEFAULTS }
    }

    /** True if [text] is a well-formed support.json worth caching. */
    fun isUsable(text: String?): Boolean = text != null && text.length <= MAX_JSON_CHARS &&
        try { JSONObject(text).optInt("version") == 1 } catch (_: Exception) { false }
}
