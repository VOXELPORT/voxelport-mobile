package com.voxelport.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * "Test my address": asks the public address for its Minecraft status, the way the game's server list does. The request
 * leaves the phone, reaches the VoxelPort relay and comes back into the phone, so a green result means friends can join.
 * A red result says in plain words which step failed.
 */
object AddressTest {
    sealed class Result {
        /** The server answered. */
        data class Online(val version: String, val players: Int, val max: Int, val ms: Int) : Result()
        /** The name did not resolve: no internet, a VPN/Private DNS, or the relay's name is blocked. */
        object NoNetwork : Result()
        /** Nothing answered in time. */
        object TimedOut : Result()
        /** The relay is reachable but refused the connection. */
        object Refused : Result()
        /** Connected, but the server behind it did not answer: it is still starting, or stopped. */
        object NoServer : Result()
        /** Something answered, but not like a Minecraft server. */
        object NotMinecraft : Result()
    }

    /** Runs the test. Blocking: call it off the main thread. */
    fun run(host: String, port: Int, timeoutMs: Int = 8_000): Result {
        val start = System.nanoTime()
        try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.tcpNoDelay = true
                val out = DataOutputStream(s.getOutputStream())
                out.write(handshake(host, port))
                out.write(byteArrayOf(0x01, 0x00)) // status request: length 1, id 0
                out.flush()
                val json = readStatus(s.getInputStream()) ?: return Result.NoServer
                val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
                return parse(json, ms)
            }
        } catch (e: UnknownHostException) {
            return Result.NoNetwork
        } catch (e: SocketTimeoutException) {
            return Result.TimedOut
        } catch (e: ConnectException) {
            return if (e.message?.contains("refused", ignoreCase = true) == true) Result.Refused else Result.TimedOut
        } catch (e: java.io.EOFException) {
            return Result.NoServer
        } catch (e: java.io.IOException) {
            return Result.NoServer
        }
    }

    /** Packet 0x00 "handshake", asking for the status (next state 1). */
    fun handshake(host: String, port: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeVarInt(body, 0x00)
        writeVarInt(body, 767) // protocol number: any works for a status request
        val h = host.toByteArray(Charsets.UTF_8)
        writeVarInt(body, h.size)
        body.write(h)
        body.write(port shr 8 and 0xFF)
        body.write(port and 0xFF)
        writeVarInt(body, 1)
        val framed = ByteArrayOutputStream()
        writeVarInt(framed, body.size())
        framed.write(body.toByteArray())
        return framed.toByteArray()
    }

    /** Reads the status response packet and returns its JSON, or null if the server closed or sent something else. */
    fun readStatus(input: InputStream): String? {
        val din = DataInputStream(input)
        val length = readVarInt(din)
        if (length <= 0 || length > 1_000_000) return null
        val id = readVarInt(din)
        if (id != 0x00) return null
        val textLen = readVarInt(din)
        if (textLen <= 0 || textLen > length) return null
        val bytes = ByteArray(textLen)
        din.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun parse(json: String, ms: Int): Result = try {
        val o = JSONObject(json)
        val players = o.optJSONObject("players")
        Result.Online(
            version = o.optJSONObject("version")?.optString("name").orEmpty(),
            players = players?.optInt("online") ?: 0,
            max = players?.optInt("max") ?: 0,
            ms = ms,
        )
    } catch (e: org.json.JSONException) {
        Result.NotMinecraft
    }

    /** What to tell the person. Second value: true when everything is fine. */
    fun explain(r: Result): Pair<String, Boolean> = when (r) {
        is Result.Online -> t("Your address works. Friends can join. ({ms} ms, {players}/{max} players)", "ms" to r.ms, "players" to r.players, "max" to r.max) to true
        Result.NoNetwork -> t("This phone can't find the VoxelPort relay. Check your internet, and turn off any VPN, ad-blocker or Private DNS, then test again.") to false
        Result.TimedOut -> t("The test timed out. The connection may be slow, or something on this network is blocking it. Try another network, then test again.") to false
        Result.Refused -> t("The relay refused the connection. Make the server public again, then test again.") to false
        Result.NoServer -> t("The relay answered, but your server didn't. It may still be starting. Wait for Online, then test again.") to false
        Result.NotMinecraft -> t("Something answered, but it didn't look like a Minecraft server. Restart the server and test again.") to false
    }

    fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write(v and 0x7F or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 28) throw java.io.IOException("VarInt too long")
        }
    }
}
