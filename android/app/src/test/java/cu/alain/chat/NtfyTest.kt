package cu.alain.chat

import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.net.Ntfy
import cu.alain.chat.net.PingRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests del canal ntfy (D104): derivación del tema, construcción y parseo del
 * ping. Espejo JVM del e2e_ntfy.py (32/32 contra producción + ntfy.sh real);
 * el KAT del tema está cruzado con el espejo Python (misma regla que
 * CryptoRoomsTest: los formatos los fijan KATs, no implementaciones).
 */
class NtfyTest {

    // roomKey = 0x00..0x1f, roomId fijo — topic esperado calculado con el
    // espejo Python (hkdf/b32 de e2e_rooms_client.py, validado en producción).
    private val katRoomKey = ByteArray(32) { it.toByte() }
    private val katRoomId = "AAAA1111-2222-3333-4444-555555555555"
    private val KAT_TOPIC = "cj7psequuzrkgety6wpxakhkruq"

    @Test
    fun `kat del tema coincide con el espejo python`() {
        assertEquals(KAT_TOPIC, Crypto.ntfyTopic(katRoomKey, katRoomId))
    }

    @Test
    fun `tema con formato c+26 base32 minusculas`() {
        val t = Crypto.ntfyTopic(katRoomKey, katRoomId)
        assertEquals(27, t.length)
        assertEquals('c', t[0])
        assertTrue(t.substring(1).all { it in 'a'..'z' || it in '2'..'7' })
        // HKDF interna del KAT: 16 bytes exactos (4fdf…51a4), verificada con el espejo
        val other = Crypto.ntfyTopic(ByteArray(32) { (it + 7).toByte() }, katRoomId)
        assertNotEquals(t, other)
        val otherRoom = Crypto.ntfyTopic(katRoomKey, "00000000-0000-0000-0000-000000000000")
        assertNotEquals(t, otherRoom)
        // determinista (mismo input → mismo output: el receptor deriva lo mismo)
        assertEquals(t, Crypto.ntfyTopic(katRoomKey, katRoomId))
    }

    private fun row(mid: String, nonce: String, ct: String, ts: Long = 1700000000000) =
        PingRow(mid, nonce, ct, ts)

    @Test
    fun `build_ping lleva hasta 8 filas y el parseo es roundtrip`() {
        val rows = (1..10).map { row("m$it", "n$it", "c$it", 1700000000000 + it) }
        val ping = Ntfy.buildPing("room-x", "uid-a", "amelia", rows)
        val parsed = Ntfy.parsePing(ping)!!
        assertEquals("room-x", parsed.roomId)
        assertEquals(8, parsed.rows.size) // 10 filas → las 8 últimas (presupuesto anti-spam)
        // orden posicional del /sync: [msgId, uid, seq, nonce, ct, uname, ts]
        val first = parsed.rows.first()
        assertEquals("m3", first.msgId)        // takeLast(8) conserva el orden
        assertEquals("uid-a", first.senderId)
        assertEquals(0L, first.seq)            // 0 = desconocida hasta el /sync
        assertEquals("n3", first.nonceB64)
        assertEquals("c3", first.ctB64)
        assertEquals("amelia", first.senderUname)
        assertEquals(1700000000003L, first.ts)
        assertEquals("room-x", first.roomId)   // el sobre inyecta el roomId en cada fila
    }

    @Test
    fun `build_ping degrada a despertador puro cuando el sobre no cabe`() {
        val gordo = "x".repeat(3600)
        val ping = Ntfy.buildPing("room-y", "uid-a", "amelia", listOf(row("m1", "n", gordo)))
        assertFalse(ping.contains("\"m\""))
        val parsed = Ntfy.parsePing(ping)!!
        assertEquals("room-y", parsed.roomId)
        assertTrue(parsed.rows.isEmpty()) // puro: el /sync que provoca trae el lote
        // y JSONObject serializa compacto: {"v":1,"r":...}
        val j = JSONObject(ping)
        assertEquals(1, j.getInt("v"))
        assertEquals("room-y", j.getString("r"))
        assertTrue(!j.has("m"))
    }

    @Test
    fun `parse_ping tira attachment, basura, v equivocada y m de mas de 8`() {
        assertNull(Ntfy.parsePing("You received a file: attachment.txt")) // hallazgo H25
        assertNull(Ntfy.parsePing("no json"))
        assertNull(Ntfy.parsePing(""))
        assertNull(Ntfy.parsePing("{\"v\":2,\"r\":\"x\"}"))
        assertNull(Ntfy.parsePing("{\"v\":1}")) // sin r
        // m > 8 filas → degradado a puro (presupuesto anti-spam, no error)
        val big = (1..9).map { row("m$it", "n", "c") }
        val ping9 = Ntfy.buildPing("r9", "u", "un", big) // build ya solo pone 8: fabricar a mano
        val handmade = JSONObject().put("v", 1).put("r", "r9").put(
            "m", org.json.JSONArray().apply { (1..9).forEach { put(org.json.JSONArray().put("m$it").put("u").put(0).put("n").put("c").put("un").put(1L)) } }
        ).toString()
        assertEquals(8, Ntfy.parsePing(ping9)!!.rows.size)
        assertTrue(Ntfy.parsePing(handmade)!!.rows.isEmpty())
        // fila con 6 campos / ct fuera de presupuesto → puro
        val six = JSONObject().put("v", 1).put("r", "r9").put(
            "m", org.json.JSONArray().apply { put(org.json.JSONArray().put("m1").put("u").put(0).put("n").put("c").put(1L)) } // 6 campos
        ).toString()
        assertTrue(Ntfy.parsePing(six)!!.rows.isEmpty())
        val giant = JSONObject().put("v", 1).put("r", "r9").put(
            "m", org.json.JSONArray().apply { put(org.json.JSONArray().put("m1").put("u").put(0).put("n").put("y".repeat(12001)).put("un").put(1L)) }
        ).toString()
        assertTrue(Ntfy.parsePing(giant)!!.rows.isEmpty())
        // seq o ts negativos → puro
        val neg = JSONObject().put("v", 1).put("r", "r9").put(
            "m", org.json.JSONArray().apply { put(org.json.JSONArray().put("m1").put("u").put(-5).put("n").put("c").put("un").put(1L)) }
        ).toString()
        assertTrue(Ntfy.parsePing(neg)!!.rows.isEmpty())
    }

    @Test
    fun `ping puro se parsea como despertador`() {
        val parsed = Ntfy.parsePing("{\"v\":1,\"r\":\"probe\"}")!!
        assertEquals("probe", parsed.roomId)
        assertTrue(parsed.rows.isEmpty())
    }

    @Test
    fun `constantes del protocolo`() {
        assertEquals("https://ntfy.sh", C.NTFY_DEFAULT)
        assertTrue(C.NTFY_PING_MAX in 1..4000) // por debajo del límite del server (4096→attachment)
        assertEquals(8, C.NTFY_PING_ROWS)
    }
}
