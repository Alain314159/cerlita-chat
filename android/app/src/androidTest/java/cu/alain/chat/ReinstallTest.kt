package cu.alain.chat

import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cu.alain.chat.core.C
import cu.alain.chat.crypto.DmCrypto
import cu.alain.chat.engine.AppState
import cu.alain.chat.mock.MockBackend
import kotlinx.coroutines.runBlocking
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
import java.io.File

/**
 * D119 (Fase I del informe 1.0): el CRITERIO DE SALIDA de la brecha 3,
 * ejecutado de extremo a extremo en un Android real — «una prueba que
 * reinstale la app, registre una segunda clave y recupere los chats directos
 * sin intervención manual más allá de tocar el aviso».
 *
 * Historia continua (un solo proceso):
 *
 *   1. CREA: el teléfono instala la app, crea su cuenta y su chat directo con
 *      @cerlita (par simulado por el backend local: el test controla el
 *      keypair del par y fabrica sus wraps con el PROPIO DmCrypto de la app).
 *   2. REINSTALA: logout (borra BD/vault/sesión) + device_key.bin borrado a
 *      mano = teléfono nuevo: la siguiente entrada genera OTRO par X25519 y
 *      lo registra como SEGUNDA clave del usuario. El /rooms del simulado
 *      marca el chat directo kind=1 pending=0 → el motor debe ALZAR EL AVISO
 *      de re-emparejamiento (dmRecovery).
 *   3. TOCA EL AVISO: requestDmRecovery() (lo que hace el botón del banner)
 *      → dm_stuck al servidor simulado.
 *   4. EL PAR RESPONDE: el test fabrica el wrap NUEVO para la clave del
 *      teléfono reinstalado (X25519 real) y lo sirve por el /sync — igual que
 *      haría producción al procesar el rr. La app lo abre SOLO, restaura la
 *      MISMA roomKey original y el aviso desaparece. Después, enviar un
 *      mensaje al chat recuperado debe funcionar como siempre.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class ReinstallTest {

    companion object {

        private lateinit var backend: MockBackend
        private lateinit var app: ChatApp

        /** Keypair del PAR (el test es «el otro teléfono»): la privada del
         *  par nunca sale de este test, como nunca sale del teléfono real. */
        private val peerKp = DmCrypto.newKeyPair()

        /** Material de la sala guardado ANTES de la reinstalación: la prueba
         *  de que el chat recuperado tiene EXACTAMENTE la misma roomKey. */
        private var savedRoomId = ""
        private var savedKeyB64 = ""
        private var savedSaltB64 = ""

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

            // el par simulado: una clave activa (la que el test controla)
            backend.fakePeer = Triple(
                "u-par-0001", "cerlita",
                listOf("k-par-1" to peerKp.pubB64()),
            )

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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

        fun esperarDb(ms: Long = 30_000, cond: suspend () -> Boolean): Boolean =
            esperar(ms) { runBlocking { cond() } }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    // ── 1 · CREA: alta + chat directo con el par (instalación original) ──

    @Test
    fun b1_crea_cuenta_y_chat_directo() {
        runBlocking {
            val pub = app.store.ensureDeviceKeyPair().first.pubB64
            val r = app.api.register("tester", "clave-de-prueba", app.prefs.deviceId, "CODIGO-PRUEBA", pub)
            app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId, cu.alain.chat.net.parseIsoMs(r.exp))
        }
        assertTrue("AppState → Main", esperar { app.engine.state.value is AppState.Main })

        val room = runBlocking { app.engine.createDm("cerlita").getOrThrow() }
        assertEquals("kind = chat directo", C.ROOM_DM, room.kind)
        assertTrue("clave de la sala en el vault", app.engine.hasKey(room.roomId))

        // el servidor simulado vio el wrap multi-dispositivo para el par
        assertTrue(
            "dm_new subió el wrap para la clave del par (ws)",
            backend.wrapUploads.any { it.first == room.roomId && it.second == 1 },
        )

        // guardar el material original: la prueba de la recuperación
        runBlocking {
            val (k64, s64) = app.store.roomKeys().getValue(room.roomId)
            savedRoomId = room.roomId
            savedKeyB64 = k64
            savedSaltB64 = s64
        }
    }

    // ── 2 · REINSTALA: nueva clave, misma cuenta → el aviso se alza ──

    @Test
    fun b2_reinstala_segunda_clave_y_aviso_de_recuperacion() {
        // «desinstalar»: sesión+BD+vault fuera (logout) y el par X25519 del
        // teléfono BORRADO — la siguiente entrada fabrica otro par, como un
        // teléfono nuevo de verdad.
        app.engine.logout(apiLogout = true)
        assertTrue("BD vacía tras la reinstalación", esperarDb { app.db.roomDao().all().isEmpty() })
        File(app.filesDir, "device_key.bin").delete()

        val oldPub = peerSavedPub()
        runBlocking {
            val pub = app.store.ensureDeviceKeyPair().first.pubB64
            assertTrue("el teléfono nuevo tiene OTRA clave X25519", pub != oldPub)
            val r = app.api.login("tester", "clave-de-prueba", app.prefs.deviceId, pub)
            app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId, cu.alain.chat.net.parseIsoMs(r.exp))
        }
        assertTrue("Main tras entrar desde el teléfono nuevo", esperar { app.engine.state.value is AppState.Main })

        // el servidor simulado ahora conoce DOS claves del usuario (conjunto)
        assertTrue("segunda clave registrada en el servidor", esperar { backend.userKeys.size >= 2 })

        // EL AVISO: el motor detecta el chat directo sin clave ni wraps pendientes
        assertTrue(
            "dmRecovery.needed contiene el chat directo",
            esperar { app.engine.dmRecovery.value.needed.any { it.equals(savedRoomId, ignoreCase = true) } },
        )
        assertTrue("sin la clave del chat en este teléfono", !app.engine.hasKey(savedRoomId))
    }

    // ── 3 · TOCA EL AVISO: pedir el re-emparejamiento ──

    @Test
    fun b3_tocar_el_aviso_pide_re_emparejamiento() {
        app.engine.requestDmRecovery() // lo que hace el botón «Recuperar»
        assertTrue(
            "dm_stuck llegó al servidor simulado",
            esperar { backend.stuckRooms.any { it.equals(savedRoomId, ignoreCase = true) } },
        )
    }

    // ── 4 · EL PAR RESPONDE: el wrap nuevo abre solo y el chat vuelve ──

    @Test
    fun b4_el_par_re_envuelve_y_el_chat_se_recupera_solo() {
        // El par (este test) fabrica el wrap para la clave del teléfono nuevo:
        // X25519(privDelPar, pubDelTeléfonoNuevo) → HKDF → ChaCha, AAD con MI uid.
        val myUid = app.engine.session!!.uid
        val devicePub = runBlocking { app.store.ensureDeviceKeyPair().first.pubB64 }
        val wrap = DmCrypto.wrap(
            peerKp.priv,
            Base64.decode(devicePub, Base64.NO_WRAP),
            savedRoomId,
            myUid,
            Base64.decode(savedKeyB64, Base64.NO_WRAP),
            Base64.decode(savedSaltB64, Base64.NO_WRAP),
        )
        backend.deliverDm(
            savedRoomId, "u-par-0001", "cerlita",
            peerKp.pubB64(),
            Base64.encodeToString(wrap, Base64.NO_WRAP),
        )
        // despertar el sync por el canal real del timbre (el FGS del proceso)
        esperar(20_000) { backend.pushDoorbell(savedRoomId) }

        assertTrue(
            "el chat directo VOLVIÓ: misma roomKey que la original",
            esperarDb(60_000) {
                val ks = app.store.roomKeys()[savedRoomId.lowercase()]
                ks != null && ks.first == savedKeyB64 && ks.second == savedSaltB64
            },
        )
        assertTrue(
            "el aviso se limpió",
            esperar { app.engine.dmRecovery.value.needed.isEmpty() && app.engine.dmRecovery.value.waiting.isEmpty() },
        )
        assertTrue("ack del wrap al servidor", esperar { backend.ackedDms.any { it.equals(savedRoomId, ignoreCase = true) } })

        // y el chat recuperado FUNCIONA: enviar un mensaje llega al outbox
        val msgId = runBlocking {
            val id = app.engine.send(savedRoomId, "recuperado").getOrThrow()
            cu.alain.chat.work.WorkSync.kickOutbox(app)
            app.engine.poke()
            id
        }
        assertTrue(
            "mensaje al chat recuperado con ✓",
            esperarDb(45_000) { app.db.msgDao().byId(msgId)?.state == C.ST_SENT },
        )
        assertNotNull("sala sigue en la BD", runBlocking { app.db.roomDao().get(savedRoomId) })
    }

    /** La pública que el teléfono tenía ANTES de la reinstalación (la saca
     *  del registro del servidor simulado: la 1ª clave registrada). */
    private fun peerSavedPub(): String = backend.userKeys.firstOrNull()?.second ?: ""
}
