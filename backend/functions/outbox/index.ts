// Edge Function: outbox — POST lote de mensajes cifrados (idempotente)
// POST /functions/v1/outbox  (Bearer token de sesión)
// Body: {room: "<room_id>", m: [[msg_id, ct_b64, nonce_b64, ts_ms?], ...]} (máx 64)
// El msg_id (UUIDv7 del cliente) es PK: reintentos seguros por construcción.
// La sala la identifica room_id (derivado de la roomKey en el cliente);
// el servidor NO conoce el destinatario por username — solo la membresía.
import postgres from "npm:postgres@3.4.9";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const enc = new TextEncoder();
const DEBUG = Deno.env.get("DEBUG") === "1";
const json = (status: number, obj: unknown) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_BATCH = 64;
const MAX_CT = 8192;

function unb64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}
const fail = (e: unknown) => DEBUG ? { e: "internal", d: String(e) } : { e: "internal" };

// ── FCM v1 (D121): JWT RS256 de la service account con WebCrypto ──────────
// Mismo contrato que el resto del backend: best-effort, nunca lanza, y sin
// FCM_SA_JSON en el entorno simplemente no hay 5ª capa (todo sigue igual).
interface FcmSa {
  client_email: string;
  private_key: string;
  project_id: string;
}

/** Base64url de ASCII (solo header/claims del JWT — sin Unicode). */
function b64url(s: string): string {
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** PEM PKCS8 → DER para importKey. */
function pemToDer(pem: string): Uint8Array {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  return unb64(body);
}

/** Access token OAuth2 firmado con la SA (cacheado ~50 min en el proceso). */
async function fcmAccessToken(sa: FcmSa): Promise<string> {
  const g = globalThis as { __fcmTok?: { tok: string; exp: number } };
  if (g.__fcmTok && g.__fcmTok.exp > Date.now()) return g.__fcmTok.tok;
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const unsigned = b64url(JSON.stringify(header)) + "." + b64url(JSON.stringify(claims));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(unsigned)));
  // Firma → base64 → base64url. El binario crudo NO es base64: sin el btoa
  // Google recibía bytes crudos en la firma y respondía oauth 400 (bug del
  // primer ejercicio real de este camino — antes siempre degradaba a no-sa).
  const sigB64 = u8ToB64(sig).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const jwt = `${unsigned}.${sigB64}`;
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!r.ok) throw new Error("oauth " + r.status);
  const j = await r.json();
  g.__fcmTok = { tok: j.access_token, exp: Date.now() + 50 * 60 * 1000 };
  return j.access_token;
}

/** ByteA → base64 para armar las filas del ping (espejo de Ntfy.buildPing). */
function u8ToB64(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s);
}

const FCM_PING_MAX = 3400; // == C.NTFY_PING_MAX: data message FCM admite 4 KB
const FCM_PING_ROWS = 8;   // == C.NTFY_PING_ROWS

/**
 * 5ª capa de despertar (D121): ping FCM a las sesiones CON token de los
 * demás miembros (mis OTROS dispositivos incluidos — multi-dispositivo D119).
 * El payload es EXACTAMENTE el mismo formato del ping ntfy, así el receptor
 * reutiliza parser, ingest y /sync de confirmación: cero código nuevo ahí.
 * Devuelve un string de diagnóstico (solo se ve con DEBUG=1).
 */
async function sendFcm(
  room: string,
  senderUid: string,
  senderDevice: string,
  insertedIds: string[],
): Promise<string> {
  const saRaw = Deno.env.get("FCM_SA_JSON");
  if (!saRaw) return "no-sa"; // despliegue sin FCM configurado: capa apagada
  const sa: FcmSa = JSON.parse(saRaw);

  // Destinatarios: sesiones vivas con token, miembros de la sala, excluida
  // SOLO la sesión emisora (uid+device). SELECT DISTINCT: varias sesiones del
  // mismo user+device (re-login) no duplican si registraron el mismo token
  // (la función push ya lo limpia, pero DISTINCT es la red de seguridad).
  const dests = await sql`
    select distinct s.fcm_token as tok from sessions s
    join room_members rm on rm.user_id = s.user_id
    where rm.room_id = ${room}::uuid
      and s.revoked_at is null and s.expires_at > now()
      and s.fcm_token is not null
      and (s.user_id <> ${senderUid}::uuid or s.device_id <> ${senderDevice})`;
  if (dests.length === 0) return "no-dest";

  // Filas del ping: los recién insertados con el MISMO orden posicional que
  // /sync ([msgId, uid, seq=0, nonce, ct, uname, ts_ms]) — seq la llena el
  // /sync del receptor, igual que en ntfy.
  const [sender] = await sql`select username from users where id = ${senderUid}::uuid limit 1`;
  const uname = String(sender?.username ?? "");
  const rows = await sql`
    select m.msg_id as mid, encode(m.nonce, 'base64') as nb,
           encode(m.ciphertext, 'base64') as cb,
           (extract(epoch from coalesce(m.client_ts, m.created_at)) * 1000)::bigint as ts
    from messages m
    where m.msg_id = any(${insertedIds}::uuid[])
    order by coalesce(m.client_ts, m.created_at) asc`;

  let ping = `{"v":1,"r":"${room}"}`; // despertador puro (fallback)
  const build = (n: number) => {
    const m = rows.slice(-n).map((r: Record<string, unknown>) =>
      `["${r.mid}","${senderUid}",0,"${r.nb}","${r.cb}","${uname}",${r.ts ?? 0}]`);
    return `{"v":1,"r":"${room}","m":[${m.join(",")}]}`;
  };
  for (const n of [FCM_PING_ROWS, 1]) {
    if (rows.length === 0) break;
    const cand = build(n);
    if (cand.length <= FCM_PING_MAX) { ping = cand; break; }
  }

  const tok = await fcmAccessToken(sa);
  const results = await Promise.allSettled(dests.map(async (d: { tok: string }) => {
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${tok}`, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token: d.tok,
          android: { priority: "HIGH" }, // data message: despierta hasta con la app muerta
          data: { p: ping },
        },
      }),
    });
    if (r.status === 404 || r.status === 400) {
      // Token muerto (uninstall/rotado): auto-limpieza para no volver a intentar
      await sql`update sessions set fcm_token = null where fcm_token = ${d.tok}`;
    }
    return r.status;
  }));
  const ok = results.filter((x) => x.status === "fulfilled" && x.value < 300).length;
  return `sent ${ok}/${dests.length} (${ping.length}B)`;
}

Deno.serve(async (req) => {
  try {
    if (req.method !== "POST") return json(405, { e: "method" });
    const h = req.headers.get("authorization") ?? "";
    if (!h.startsWith("Bearer ")) return json(401, { e: "unauthorized" });
    const hash = toHex(sha256(enc.encode(h.slice(7))));

    const [me] = await sql`
      select s.user_id as uid, s.device_id as did from sessions s
      where s.token_hash = ${hash} and s.revoked_at is null and s.expires_at > now()
      limit 1`;
    if (!me) return json(401, { e: "unauthorized" });

    const ctType = req.headers.get("content-type") ?? "";
    if (!ctType.includes("application/json")) return json(415, { e: "bad_content_type" });
    const body = await req.json().catch(() => null);
    if (!body || typeof body.room !== "string") return json(400, { e: "bad_request" });
    const room = body.room.trim().toLowerCase();
    if (!UUID_RE.test(room)) return json(400, { e: "bad_room" });
    if (!Array.isArray(body.m) || body.m.length === 0) return json(400, { e: "empty" });
    if (body.m.length > MAX_BATCH) return json(413, { e: "too_many" });

    // Validar el lote completo ANTES de escribir nada (todo o nada)
    const prepared: { msg_id: string; ct: Uint8Array; nonce: Uint8Array; ts: Date | null }[] = [];
    for (const m of body.m) {
      if (!Array.isArray(m) || m.length < 3) return json(400, { e: "bad_msg" });
      const [mid, ctb, nb, ts] = [m[0], m[1], m[2], m[3]];
      if (typeof mid !== "string" || !UUID_RE.test(mid)) return json(400, { e: "bad_msg_id" });
      let ctB: Uint8Array, nB: Uint8Array;
      try { ctB = unb64(String(ctb)); nB = unb64(String(nb)); } catch { return json(400, { e: "bad_b64" }); }
      if (ctB.length < 16 || ctB.length > MAX_CT) return json(400, { e: "bad_ct_size" });
      if (nB.length !== 12) return json(400, { e: "bad_nonce" });
      const clientTs = typeof ts === "number" && ts > 0 && ts < 4102444800000 ? new Date(ts) : null;
      prepared.push({ msg_id: mid, ct: ctB, nonce: nB, ts: clientTs });
    }

    // Membresía del emisor en la sala (404 genérico: no revela si la sala
    // existe o no — el room_id es una capability de 128 bits de todos modos).
    const uid = String(me.uid);
    const [member] = await sql`
      select 1 as ok from room_members
      where room_id = ${room}::uuid and user_id = ${uid}::uuid limit 1`;
    if (!member) return json(404, { e: "no_room" });

    // RETURNING da SOLO las filas nuevas (los conflictos del reintento no):
    // es exactamente la señal para tocar el timbre una única vez por mensaje.
    const inserted = await sql`
      insert into messages ${
        sql(prepared.map((p) => ({
          msg_id: p.msg_id, room_id: room, sender_id: uid,
          sender_device_id: me.did, ciphertext: p.ct, nonce: p.nonce, client_ts: p.ts,
        })), "msg_id", "room_id", "sender_id", "sender_device_id", "ciphertext", "nonce", "client_ts")
      } on conflict (msg_id) do nothing returning msg_id`;

    // Ack de los que existen (insertados ahora + aceptados en reintentos previos)
    const ids = prepared.map((p) => p.msg_id);
    const acked = await sql`
      select msg_id from messages
      where msg_id = any(${ids}::uuid[]) and room_id = ${room}::uuid and sender_id = ${uid}`;

    await sql`update sessions set last_seen_at = now() where token_hash = ${hash}`;

    // ── Timbre Realtime (D63) ─────────────────────────────────────────────────
    // Aviso a los dispositivos conectados de la sala: SOLO el roomId, jamás
    // contenido. Cada cliente responde con /sync (la fuente de verdad). Es
    // best-effort: si falla, el sync periódico de la app (15 min) alcanza.
    // Hallazgos 10-11:
    //  10. El topic del REST broadcast va SIN prefijo "realtime:" (con prefijo
    //      el gateway responde 202 pero nadie lo recibe).
    //  11. El canal no es privado → la publishable/anon key ES la autorización
    //      del broadcast (la sb_secret_ nueva da 401/403 en Realtime; el canal
    //      es inadivinable: roomId = HKDF(roomKey) de 128 bits).
    let bell = "skip"; // diagnóstico solo con DEBUG=1
    let fcm = "skip";
    if (inserted.length > 0) {
      try {
        const surl = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
        const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
        if (surl && anon) {
          const r = await fetch(`${surl}/realtime/v1/api/broadcast`, {
            method: "POST",
            headers: {
              "Authorization": `Bearer ${anon}`,
              "apikey": anon,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              messages: [{
                topic: `room-${room}`,
                event: "new-msg",
                payload: { roomId: room },
              }],
            }),
          });
          bell = `http_${r.status}`;
          if (!r.ok) bell += " " + (await r.text()).slice(0, 120);
        } else {
          bell = `env url=${!!surl} anon=${!!anon}`;
        }
      } catch (e) {
        bell = "err " + String(e).slice(0, 160);
        console.error("doorbell error:", e);
        // timbre caído ≠ mensaje perdido: el sync programado lo trae igual
      }
    }
    // D121: 5ª capa — FCM a los demás dispositivos con Play Services. Igual
    // que el timbre: best-effort, un fallo aquí NUNCA rompe el ack del lote.
    if (inserted.length > 0) {
      try {
        fcm = await sendFcm(room, uid, String(me.did), inserted.map((r: { msg_id: string }) => r.msg_id));
      } catch (e) {
        fcm = "err " + String(e).slice(0, 120);
        // FCM caído ≠ mensaje perdido: timbre/ntfy/alarma/worker lo cubren
      }
    }
    if (DEBUG) console.log("doorbell:", bell, "fcm:", fcm, "inserted:", inserted.length);

    return json(200, DEBUG
      ? { a: acked.map((r) => r.msg_id), bell, fcm, n_ins: inserted.length }
      : { a: acked.map((r) => r.msg_id) });
  } catch (e) {
    console.error("outbox error:", e);
    return json(500, fail(e));
  }
});
