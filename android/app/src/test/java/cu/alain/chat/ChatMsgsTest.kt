package cu.alain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cu.alain.chat.profile.ChatMsgs

/**
 * S2/D110: framing de los mensajes de control del chat (responder, editar,
 * borrar, reaccionar). Mismo contrato que los demás: prefijo \u0000 que un
 * texto normal jamás produce, parse tolerante (null, nunca excepción) y
 * redondeo exacto.
 */
class ChatMsgsTest {

    @Test
    fun `reply redondea id y texto`() {
        val enc = ChatMsgs.Reply.encode("m-123", "hola mi amor")
        assertTrue(ChatMsgs.Reply.isReply(enc))
        assertFalse(ChatMsgs.Reply.isReply("hola mi amor"))
        val (id, text) = ChatMsgs.Reply.parse(enc)!!
        assertEquals("m-123", id)
        assertEquals("hola mi amor", text)
    }

    @Test
    fun `reply corrupto da null`() {
        assertNull(ChatMsgs.Reply.parse("\u0000Q{}"))
        assertNull(ChatMsgs.Reply.parse("\u0000Qno-json"))
        assertNull(ChatMsgs.Reply.parse("texto normal"))
    }

    @Test
    fun `edit redondea y acota texto`() {
        val enc = ChatMsgs.Edit.encode("m-9", "texto corregido")
        assertTrue(ChatMsgs.Edit.isEdit(enc))
        val (id, t) = ChatMsgs.Edit.parse(enc)!!
        assertEquals("m-9", id)
        assertEquals("texto corregido", t)
        // texto enorme → acotado, nunca rompe el sobre del servidor
        val big = ChatMsgs.Edit.encode("m-9", "x".repeat(20_000))
        assertTrue(ChatMsgs.Edit.parse(big)!!.second.length <= ChatMsgs.Edit.MAX_TEXT)
    }

    @Test
    fun `delete lleva solo el id`() {
        val enc = ChatMsgs.Delete.encode("m-42")
        assertEquals("m-42", ChatMsgs.Delete.parse(enc))
        assertNull(ChatMsgs.Delete.parse("\u0000D{}"))
    }

    @Test
    fun `reaction distingue add y remove`() {
        val on = ChatMsgs.Reaction.encode("m-1", "❤️", on = true)
        val off = ChatMsgs.Reaction.encode("m-1", "❤️", on = false)
        val pOn = ChatMsgs.Reaction.parse(on)!!
        val pOff = ChatMsgs.Reaction.parse(off)!!
        assertEquals("m-1", pOn.msgId)
        assertEquals("❤️", pOn.emoji)
        assertTrue(pOn.on)
        assertFalse(pOff.on)
        assertNull(ChatMsgs.Reaction.parse("\u0000R{\"i\":\"x\"}"))
    }

    @Test
    fun `prefijos no colisionan entre si ni con texto`() {
        val bodies = listOf(
            ChatMsgs.Reply.encode("a", "b"),
            ChatMsgs.Edit.encode("a", "b"),
            ChatMsgs.Delete.encode("a"),
            ChatMsgs.Reaction.encode("a", "x", true),
        )
        // cada uno se reconoce como SU tipo y como nada más
        assertTrue(ChatMsgs.Reply.isReply(bodies[0]) && !ChatMsgs.Reply.isReply(bodies[1]))
        assertTrue(ChatMsgs.Edit.isEdit(bodies[1]) && !ChatMsgs.Edit.isEdit(bodies[0]))
        assertTrue(ChatMsgs.Delete.isDelete(bodies[2]))
        assertTrue(ChatMsgs.Reaction.isReaction(bodies[3]))
        bodies.forEach { b ->
            assertFalse(ChatMsgs.Reply.isReply("plain $b")) // texto con prefijo DENTRO no vale
        }
    }
}
