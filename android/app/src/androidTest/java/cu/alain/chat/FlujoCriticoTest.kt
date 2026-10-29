package cu.alain.chat

import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.engine.AppState
import cu.alain.chat.mock.MockBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * D115 (Fase V del informe 1.0): la PRIMERA prueba de instrumentación del
 * proyecto. Brecha 2: los cinco defectos más graves de la historia de la app
 * (ICE antes que SDP, altavoz de API 31+, barra duplicada, botones del sistema
 * que no se ocultaban, tema «que no se parecía en nada») eran INVISIBLES para
 * la suite JVM — pasaban todos en verde y morían en el teléfono real.
 *
 * Esta suite ejecuta en un Android REAL (emulador ATD en CI, KVM) el flujo
 * que define el producto, con el stack completo de verdad: AndroidKeyStore
 * sellando las claves, Room persistiendo, OkHttp hablando con un backend
 * simulado EN EL DISPOSITIVO (127.0.0.1 — sin tocar producción), el FGS del
 * timbre vivo y el protocolo Phoenix del WebSocket despertando la sincronización.
 *
 * Orden = historia continua (un solo proceso): alta → entrada → envío →
 * recepción CON LA APP EN SEGUNDO PLANO (ciclo real de Activity: onStop → el
 * FGS del timbre sigue vivo → broadcast new-msg → /sync → descifrar → BD).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class FlujoCriticoTest {

    companion object {

        private lateinit var backend: MockBackend
        private lateinit var app: ChatApp

        @BeforeClass
        @JvmStatic
        fun arrancarBackendEnElDispositivo() {
            backend = MockBackend()
            backend.start()

            app = ApplicationProvider.getApplicationContext()
            // TODO el tráfico de la app (API + ntfy) va al simulado local:
            // cero llamadas a producción, cero usuarios gastados (H16).
            app.prefs.baseUrl = backend.url
            app.prefs.ntfyUrl = backend.url

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            // El diálogo de permisos del sistema no es automatizable desde
            // Compose: pre-conceder. También la exención de batería (para que
            // el diálogo explicativo de AppRoot no salte en el flujo).
            device.executeShellCommand("pm grant cu.alain.chat android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("dumpsys deviceidle whitelist +cu.alain.chat")
        }

        @AfterClass
        @JvmStatic
        fun pararBackend() {
            backend.stop()
        }

        /** Espera activa con tope: la app es asíncrona (colas, timbre, retries). */
        fun esperar(ms: Long = 30_000, cada: Long = 250, cond: () -> Boolean): Boolean {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < ms) {
                if (cond()) return true
                Thread.sleep(cada)
            }
            return cond()
        }

        /** Igual con acceso a BD/suspend (Room es suspend). */
        fun esperarDb(ms: Long = 30_000, cond: suspend () -> Boolean): Boolean =
            esperar(ms) { runBlocking { cond() } }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    // ── 1 · ALTA: crear cuenta → sesión sellada en el KeyStore real → Main ──

    @Test
    fun a1_alta_crea_sesion_y_llega_a_main() {
        compose.onNodeWithText("Cerlita Chat").assertExists() // Login en pantalla

        runBlocking {
            val pub = app.store.ensureDeviceKeyPair().first.pubB64 // X25519 del teléfono
            val r = app.api.register("tester", "clave-de-prueba", app.prefs.deviceId, "CODIGO-PRUEBA", pub)
            app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId, cu.alain.chat.net.parseIsoMs(r.exp))
        }

        assertTrue("AppState → Main tras el alta", esperar { app.engine.state.value is AppState.Main })
        assertNotNull("sesión sellada en el vault", app.engine.session)

        // La UI real acompaña al estado: onboarding de perfil tras entrar
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Tu perfil").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ── 2 · ENTRADA: cerrar sesión (borra lo local) y volver a entrar ──

    @Test
    fun a2_entrada_logout_y_login_de_nuevo() {
        app.engine.logout(apiLogout = true)
        assertTrue("AppState → Login tras cerrar sesión", esperar { app.engine.state.value is AppState.Login })
        assertTrue("la BD local se vació", esperarDb { app.db.roomDao().all().isEmpty() })

        runBlocking {
            val pub = app.store.ensureDeviceKeyPair().first.pubB64 // sobrevive al logout (D74)
            val r = app.api.login("tester", "clave-de-prueba", app.prefs.deviceId, pub)
            app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId, cu.alain.chat.net.parseIsoMs(r.exp))
        }

        assertTrue("AppState → Main tras entrar de nuevo", esperar { app.engine.state.value is AppState.Main })
        assertNotNull(app.engine.session)
    }

    // ── 3 · ENVÍO: sala + mensaje cifrado con ✓ del servidor ──

    @Test
    fun a3_envio_ack_del_outbox() {
        val (roomId, msgId) = runBlocking {
            val joined = app.engine.createRoom("Sala de prueba").getOrThrow()
            val id = app.engine.send(joined.room.roomId, "hola desde la prueba instrumentada").getOrThrow()
            cu.alain.chat.work.WorkSync.kickOutbox(app) // drenaje inmediato (igual que ChatScreen)
            app.engine.poke()
            joined.room.roomId to id
        }
        assertTrue("clave de la sala sellada en el KeyStore", app.engine.hasKey(roomId))

        assertTrue(
            "mensaje con ✓ (ack del /outbox simulado)",
            esperarDb(45_000) { app.db.msgDao().byId(msgId)?.state == C.ST_SENT },
        )
        assertTrue("el servidor simulado vio el POST del outbox", backend.acked.any { it.second == msgId })
    }

    // ── 4 · RECEPCIÓN EN SEGUNDO PLANO: push del timbre → sync → descifrar → BD ──

    @Test
    fun a4_recepcion_con_la_app_en_segundo_plano() {
        val room = runBlocking { app.db.roomDao().all().firstOrNull { it.kind == C.ROOM_GROUP } }
        assertNotNull("sala del test anterior", room)
        val roomId = room!!.roomId

        // Cifrar como haría el PAR: misma roomKey (del vault real), otro uid.
        val (k64, _) = app.store.roomKeys().getValue(roomId)
        val roomKey = Base64.decode(k64, Base64.NO_WRAP)
        val msgId = Crypto.uuid7()
        val convKey = Crypto.convKey(roomKey, roomId)
        val mk = Crypto.msgKey(convKey, msgId)
        val aad = Crypto.aad(msgId, "u-par-0001", roomId)
        val (nonce, ct) = Crypto.seal(mk, Crypto.frame("hola desde el par"), aad)

        val row = JSONArray()
            .put(msgId).put(roomId).put("u-par-0001").put(backend.nextSeq())
            .put(Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put(Base64.encodeToString(ct, Base64.NO_WRAP))
            .put("pareja").put(System.currentTimeMillis())
        backend.deliverIncoming(row)

        // App al FONDO — ciclo real de Activity: onStop → el FGS del timbre
        // (instantBg ON) mantiene vivo el WebSocket contra el backend simulado.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        assertTrue("app en segundo plano", esperar { !app.inForeground })

        // Despertar por el CANAL REAL: broadcast «new-msg» por el WS del FGS
        assertTrue("timbre WS del FGS conectado al simulado", esperar(20_000) { backend.pushDoorbell(roomId) })

        assertTrue(
            "mensaje del par recibido y DESCIFRADO con la app en 2º plano",
            esperarDb(60_000) {
                app.db.msgDao().byId(msgId)?.let { it.body == "hola desde el par" && !it.mine } == true
            },
        )
        runBlocking {
            assertEquals("estado recibido", C.ST_RECEIVED, app.db.msgDao().byId(msgId)!!.state)
            assertEquals("badge de no leídos subió", 1, app.db.roomDao().get(roomId)!!.unread)
        }
    }
}
