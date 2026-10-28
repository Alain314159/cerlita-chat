// Edge Function: auth — registro / login / logout / whoami + salas
// Auth propia con token de sesión. La frase E2EE y la roomKey NUNCA llegan aquí.
// Registro protegido por código de invitación (secret REG_INVITE_CODE).
//
// Salas (modelo rooms, doc v2 corregido):
//   {a:"room_new", r:room_id}  — crea la sala + membresía del creador.
//       room_id lo genera el CLIENTE: HKDF(roomKey, salt=roomSalt,
//       info="room-id")[0..16] como UUID. La roomKey JAMÁS viaja: el código
//       de invitación (base32(roomSalt‖roomKey), con la roomKey EN CLARO
//       dentro) se comparte por otro canal; el servidor no lo ve nunca.
//   {a:"room_join", r:room_id} — B se une tras escanear el código.
//       Capability-based: conocer el room_id (2^128) ES la invitación.
//       SIN sas_confirmed: el SAS se compara por voz SOLO entre los
//       clientes; el servidor únicamente registra "B está en la sala X".
//   {a:"room_leave", r:room_id} — salir de la sala (v0.6.0, D70).
//       Borra la membresía y TODO el historial de la sala si queda
//       vacía (messages.room_id no tiene CASCADE → borrado explícito).
//   {a:"rooms"}               — lista de salas propias (multi-dispositivo).
//
// Chats directos 1-a-1 (v0.7.0, D74) — sin código de invitación, E2EE igual:
//   {a:"search", q:"prefijo"} — busca usuarios por @nombre (máx 10, sin yo).
//   {a:"pk", pk:b64}           — (re)sube mi clave pública X25519 (32 B).
//   {a:"dm_new", r, peer, w}   — crea el chat directo del par (idempotente
//       por pareja). w = roomKey ENVUELTA para el par (X25519+HKDF+ChaCha,
//       opaca al servidor). El par la recoge por /sync (campo dm) y acusa
//       con dm_ack. Si el par ya creó el chat antes, devuelve exists=1 y
//       el wrap guardado PARA MÍ (si existe) para que pueda intentar
//       desenveloparlo ahora.
//   {a:"dm_ack", r}            — el wrap de esa sala ya fue recogido.
import postgres from "npm:postgres@3.4.9";
import { argon2id } from "@noble/hashes/argon2.js";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const ARGON = { t: 3, m: 32768, p: 1, dkLen: 32 }; // pass del login; la frase E2EE se deriva SOLO en el cliente con m=64MiB
const USERNAME_RE = /^[a-z0-9_]{3,24}$/;
const DEVICE_RE = /^[0-9a-f-]{36}$/i;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_USERS = 10;
const MAX_ROOMS_PER_USER = 8;   // A crea: conversación + comandos (bot) + margen
const MAX_ROOM_MEMBERS = 6;     // A, B, bot, + margen
const MAX_DMS_PER_USER = 12;    // chats directos por usuario (D74)
const PUB_LEN = 32;             // X25519 pública: 32 bytes exactos
const WRAP_MAX = 256;           // blob envuelto: 50 B payload + tag + margen

const enc = new TextEncoder();
const DEBUG = Deno.env.get("DEBUG") === "1";
const json = (status: number, obj: unknown) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

function b64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
function unb64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const b64url = (b: Uint8Array) => b64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}
function eq(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a[i] ^ b[i];
  return r === 0;
}
const fail = (e: unknown) => DEBUG ? { e: "internal", d: String(e) } : { e: "internal" };

// Sesión desde el header Authorization: Bearer <token_b64url>
async function authUser(req: Request) {
  const h = req.headers.get("authorization") ?? "";
  if (!h.startsWith("Bearer ")) return null;
  const tok = h.slice(7);
  if (tok.length < 20 || tok.length > 200) return null;
  const hash = toHex(sha256(enc.encode(tok)));
  const rows = await sql`
    select s.user_id as uid, s.device_id as did, u.username, s.expires_at as exp
    from sessions s join users u on u.id = s.user_id
    where s.token_hash = ${hash} and s.revoked_at is null and s.expires_at > now()
    limit 1`;
  return rows[0] ?? null;
}

async function newSession(uid: string, device: string) {
  const tok = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const hash = toHex(sha256(enc.encode(tok)));
  const rows = await sql`
    insert into sessions (user_id, device_id, token_hash)
    values (${uid}, ${device}, ${hash})
    returning expires_at`;
  return { tok, exp: rows[0].expires_at };
}

// ---------- register ----------
async function register(body: any) {
  const invite = Deno.env.get("REG_INVITE_CODE");
  if (!invite) return json(500, { e: "no_invite_config" });
  if (body?.ic !== invite) return json(403, { e: "bad_invite" });
  const u = typeof body?.u === "string" ? body.u.trim().toLowerCase() : "";
  const p = typeof body?.p === "string" ? body.p : "";
  const d = typeof body?.d === "string" ? body.d : "";
  const pk = parsePub(body?.pk);
  if (!USERNAME_RE.test(u)) return json(400, { e: "bad_username" });
  if (p.length < 8 || p.length > 512) return json(400, { e: "bad_password" });
  if (!DEVICE_RE.test(d)) return json(400, { e: "bad_device" });
  const [{ n }] = await sql`select count(*)::int as n from users`;
  if (n >= MAX_USERS) return json(403, { e: "user_cap" });

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = argon2id(enc.encode(p), salt, ARGON);
  const phc = `$argon2id$v=19$m=${ARGON.m},t=${ARGON.t},p=${ARGON.p}$${b64(salt)}$${b64(hash)}`;
  try {
    const [row] = await sql`insert into users (username, pass_hash, pub) values (${u}, ${phc}, ${pk}) returning id`;
    const s = await newSession(row.id, d);
    return json(201, { uid: row.id, u, tok: s.tok, exp: s.exp });
  } catch (e: any) {
    if (String(e?.code) === "23505") return json(409, { e: "username_taken" });
    throw e;
  }
}

/** Clave pública X25519 válida (32 B) o null. Tolerante con ausencia. */
function parsePub(raw: any): Uint8Array | null {
  if (typeof raw !== "string" || raw.length === 0) return null;
  try {
    const b = unb64(raw);
    return b.length === PUB_LEN ? b : null;
  } catch { return null; }
}

// ---------- login ----------
function parsePhc(phc: string) {
  try {
    const p = phc.split("$").filter(Boolean);
    if (p.length !== 5 || p[0] !== "argon2id") return null;
    const [m, t, pa] = p[2].split(",").map((x) => +x.split("=")[1]);
    return { m, t, p: pa, salt: unb64(p[3]), hash: unb64(p[4]) };
  } catch { return null; }
}

async function login(body: any) {
  const u = typeof body?.u === "string" ? body.u.trim().toLowerCase() : "";
  const p = typeof body?.p === "string" ? body.p : "";
  const d = typeof body?.d === "string" ? body.d : "";
  if (!USERNAME_RE.test(u) || !p || !DEVICE_RE.test(d)) return json(400, { e: "bad_request" });

  const [row] = await sql`
    select id, pass_hash, failed_logins, locked_until from users
    where username = ${u} limit 1`;
  if (!row) return json(401, { e: "bad_credentials" });

  if (row.locked_until && new Date(row.locked_until) > new Date()) {
    const wait = Math.ceil((new Date(row.locked_until).getTime() - Date.now()) / 1000);
    return json(429, { e: "locked", s: wait });
  }

  const parsed = parsePhc(row.pass_hash);
  let ok = false;
  if (parsed) {
    const calc = argon2id(enc.encode(p), parsed.salt, { t: parsed.t, m: parsed.m, p: parsed.p, dkLen: parsed.hash.length });
    ok = eq(calc, parsed.hash);
  }
  if (!ok) {
    const fails = (row.failed_logins ?? 0) + 1;
    const lockSecs = fails >= 5 ? Math.min(3600, 30 * 2 ** (fails - 5)) : 0;
    await sql`
      update users set failed_logins = ${fails},
        locked_until = case when ${lockSecs} > 0 then now() + make_interval(secs => ${lockSecs}) else null end
      where id = ${row.id}`;
    return json(401, { e: "bad_credentials" });
  }

  await sql`update users set failed_logins = 0, locked_until = null where id = ${row.id}`;
  // (re)sube la pública del teléfono (D74): best-effort, no rompe el login
  const pk = parsePub(body?.pk);
  if (pk) await sql`update users set pub = ${pk} where id = ${row.id}`;
  const s = await newSession(row.id, d);
  return json(200, { uid: row.id, u, tok: s.tok, exp: s.exp });
}

// ---------- logout ----------
async function logout(req: Request) {
  const h = req.headers.get("authorization") ?? "";
  if (!h.startsWith("Bearer ")) return json(401, { e: "unauthorized" });
  const hash = toHex(sha256(enc.encode(h.slice(7))));
  await sql`update sessions set revoked_at = now() where token_hash = ${hash} and revoked_at is null`;
  return json(200, { ok: true });
}

// ---------- room_new: crear sala (idempotente) ----------
// room_id generado por el cliente (derivado de roomKey). Si la sala ya
// existe y NO es tuya → 409 (colisión con capability ajena: no ocurre
// por azar, solo por room_id filtrado). Nunca se recibe roomKey aquí.
async function roomNew(me: any, body: any) {
  const r = typeof body?.r === "string" ? body.r.trim().toLowerCase() : "";
  if (!UUID_RE.test(r)) return json(400, { e: "bad_room" });
  const uid = String(me.uid);
  const [{ n }] = await sql`select count(*)::int as n from rooms where created_by = ${uid}::uuid`;
  if (n >= MAX_ROOMS_PER_USER) return json(403, { e: "room_cap" });
  // Dos queries SEPARADAS: en Postgres el statement principal no ve los
  // cambios de un CTE data-modifying (misma snapshot) — un WITH INSERT
  // aquí hacía que la 1ª llamada siempre devolviera 409.
  await sql`
    insert into rooms (room_id, created_by)
    values (${r}::uuid, ${uid}::uuid)
    on conflict (room_id) do nothing`;
  // ¿Sala mía o ya soy miembro? (RETURNING no sirve: ON CONFLICT DO NOTHING
  // no devuelve filas cuando la membresía ya existía — reintento legítimo.)
  const [ok] = await sql`
    select 1 as ok from rooms where room_id = ${r}::uuid and created_by = ${uid}::uuid
    union all
    select 1 as ok from room_members where room_id = ${r}::uuid and user_id = ${uid}::uuid
    limit 1`;
  if (!ok) return json(409, { e: "room_taken" });
  await sql`
    insert into room_members (room_id, user_id) values (${r}::uuid, ${uid}::uuid)
    on conflict do nothing`;
  return json(201, { ok: true });
}

// ---------- room_join: unirse con el room_id derivado del código ----------
// El SAS NO se registra: la verificación de identidad es cliente-cliente.
async function roomJoin(me: any, body: any) {
  const r = typeof body?.r === "string" ? body.r.trim().toLowerCase() : "";
  if (!UUID_RE.test(r)) return json(400, { e: "bad_room" });
  const uid = String(me.uid);
  const [room] = await sql`select room_id from rooms where room_id = ${r}::uuid limit 1`;
  if (!room) return json(404, { e: "no_room" });
  const [{ n }] = await sql`select count(*)::int as n from room_members where room_id = ${r}::uuid`;
  if (n >= MAX_ROOM_MEMBERS) return json(403, { e: "room_full" });
  await sql`
    insert into room_members (room_id, user_id) values (${r}::uuid, ${uid}::uuid)
    on conflict do nothing`;
  return json(200, { ok: true });
}

// ---------- room_leave: salir de la sala (v0.6.0, D70) ----------
// Idempotente (salir 2 veces = ok, salir de una sala ajena = ok sin efecto).
// Si la sala queda VACÍA se borra entera: mensajes + estado + fila.
// Si queda un miembro, su historial se conserva intacto y el que salió
// puede volver a entrar con el código de invitación.
async function roomLeave(me: any, body: any) {
  const r = typeof body?.r === "string" ? body.r.trim().toLowerCase() : "";
  if (!UUID_RE.test(r)) return json(400, { e: "bad_room" });
  const uid = String(me.uid);
  await sql`delete from room_members where room_id = ${r}::uuid and user_id = ${uid}::uuid`;
  // ¿Quedó vacía? → borrar historial + estado + sala (messages/room_state
  // NO tienen ON DELETE CASCADE en 0002 → borrado explícito y en orden).
  const [{ n }] = await sql`select count(*)::int as n from room_members where room_id = ${r}::uuid`;
  if (n === 0) {
    await sql`delete from messages where room_id = ${r}::uuid`;
    await sql`delete from room_state where room_id = ${r}::uuid`;
    await sql`delete from rooms where room_id = ${r}::uuid`;
  }
  return json(200, { ok: true });
}

// ---------- rooms: listar salas propias (para multi-dispositivo) ----------
// Solo ids y flag de propietario. Sin roomKey no se descifra nada;
// la roomKey se recupera con el código de invitación o el export cifrado.
async function roomList(me: any) {
  const uid = String(me.uid);
  const rows = await sql`
    select rm.room_id, (r.created_by = ${uid}::uuid) as owner
    from room_members rm join rooms r on r.room_id = rm.room_id
    where rm.user_id = ${uid}::uuid`;
  return json(200, { rooms: rows.map((x: any) => [x.room_id, x.owner ? 1 : 0]) });
}

// ---------- search: buscar usuarios por @nombre (D74) ----------
// Solo prefijo sobre el username público; nunca devuelve mi propia fila.
async function userSearch(me: any, body: any) {
  let q = typeof body?.q === "string" ? body.q.trim().toLowerCase() : "";
  q = q.replace(/[^a-z0-9_]/g, ""); // el username solo tiene estos caracteres
  if (q.length < 1 || q.length > 24) return json(400, { e: "bad_query" });
  const rows = await sql`
    select id, username, pub from users
    where username like ${q + "%"} and id <> ${me.uid}::uuid
    order by username limit 10`;
  return json(200, { r: rows.map((x: any) => [x.id, x.username, x.pub ? b64(new Uint8Array(x.pub)) : null]) });
}

// ---------- pk: (re)subir mi clave pública X25519 (D74) ----------
async function uploadPub(me: any, body: any) {
  const pk = parsePub(body?.pk);
  if (!pk) return json(400, { e: "bad_pk" });
  await sql`update users set pub = ${pk} where id = ${me.uid}::uuid`;
  return json(200, { ok: true });
}

// ---------- dm_new: crear el chat directo de la pareja (D74, idempotente) ----------
// r = roomId derivado por el CLIENTE de la roomKey nueva (HKDF, igual que
// room_new). w = roomKey+salt ENVUELTOS para el par (X25519(privA, pubB) →
// HKDF → ChaCha20-Poly1305) — el servidor ve un blob opaco de ≤256 B.
// dm_pair = least(a, b): índice UNIQUE → una sola conversación por pareja.
async function dmNew(me: any, body: any) {
  const r = typeof body?.r === "string" ? body.r.trim().toLowerCase() : "";
  const peer = typeof body?.peer === "string" ? body.peer.trim().toLowerCase() : "";
  const wrapB64 = typeof body?.w === "string" ? body.w : "";
  if (!UUID_RE.test(r) || !USERNAME_RE.test(peer)) return json(400, { e: "bad_request" });
  let wrapped: Uint8Array;
  try { wrapped = unb64(wrapB64); } catch { return json(400, { e: "bad_wrap" }); }
  if (wrapped.length < 16 || wrapped.length > WRAP_MAX) return json(400, { e: "bad_wrap" });

  const uid = String(me.uid);
  const [peerRow] = await sql`select id, pub from users where username = ${peer} limit 1`;
  if (!peerRow) return json(404, { e: "no_user" });
  if (String(peerRow.id) === uid) return json(400, { e: "self_dm" });
  if (!peerRow.pub) return json(409, { e: "peer_no_key" }); // aún no actualiza la app

  // ¿ya existe el chat de la pareja? → idempotente
  const [existing] = await sql`
    select room_id, (created_by = ${uid}::uuid) as mine from rooms
    where dm_pair = least(${uid}::uuid, ${peerRow.id}::uuid) limit 1`;
  if (existing) {
    // wrap guardado PARA MÍ (creado por el par): lo devuelvo para intentar
    // desenveloparlo YA (si mi keypair no cambió, recupero el acceso aquí).
    const [myWrap] = await sql`
      select wrapped from dm_keys
      where room_id = ${existing.room_id}::uuid and for_user = ${uid}::uuid
        and from_user = ${peerRow.id}::uuid limit 1`;
    const payload: Record<string, unknown> = { ok: true, room: existing.room_id, exists: 1 };
    if (myWrap) payload.w = b64(new Uint8Array(myWrap.wrapped));
    return json(200, payload);
  }

  // tope de chats directos por usuario
  const [{ n }] = await sql`
    select count(*)::int as n from room_members rm
    join rooms r2 on r2.room_id = rm.room_id and r2.kind = 1
    where rm.user_id = ${uid}::uuid`;
  if (n >= MAX_DMS_PER_USER) return json(403, { e: "dm_cap" });

  // crear (roomId del cliente; colisión con capability ajena → 409, igual que room_new)
  await sql`
    insert into rooms (room_id, created_by, kind, dm_pair)
    values (${r}::uuid, ${uid}::uuid, 1, least(${uid}::uuid, ${peerRow.id}::uuid))
    on conflict (room_id) do nothing`;
  const [ok] = await sql`select 1 as ok from rooms where room_id = ${r}::uuid and created_by = ${uid}::uuid limit 1`;
  if (!ok) return json(409, { e: "room_taken" });

  await sql`
    insert into room_members (room_id, user_id) values (${r}::uuid, ${uid}::uuid), (${r}::uuid, ${peerRow.id}::uuid)
    on conflict do nothing`;
  // wrap para el par (idempotente: reintento con otro roomId no puede pasar
  // porque dm_pair ya existiría → llegamos arriba; aquí solo reintentos de red)
  await sql`
    insert into dm_keys (room_id, for_user, from_user, wrapped)
    values (${r}::uuid, ${peerRow.id}::uuid, ${uid}::uuid, ${wrapped})
    on conflict (room_id, for_user) do update set wrapped = excluded.wrapped, consumed_at = null`;

  // despertar al par si está en línea (topic personal, hallazgo 10: sin prefijo)
  try {
    const surl = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
    const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    if (surl && anon) {
      await fetch(`${surl}/realtime/v1/api/broadcast`, {
        method: "POST",
        headers: { "Authorization": `Bearer ${anon}`, "apikey": anon, "Content-Type": "application/json" },
        body: JSON.stringify({
          messages: [{ topic: `user-${peerRow.id}`, event: "dm", payload: { roomId: r } }],
        }),
      });
    }
  } catch (_e) { /* best-effort: el sync periódico lo trae igual */ }

  return json(201, { ok: true, room: r, exists: 0 });
}

// ---------- dm_ack: el wrap ya fue recogido y procesado (D74) ----------
async function dmAck(me: any, body: any) {
  const r = typeof body?.r === "string" ? body.r.trim().toLowerCase() : "";
  if (!UUID_RE.test(r)) return json(400, { e: "bad_request" });
  await sql`
    update dm_keys set consumed_at = now()
    where room_id = ${r}::uuid and for_user = ${me.uid}::uuid and consumed_at is null`;
  return json(200, { ok: true });
}

// ---------- router ----------
Deno.serve(async (req) => {
  try {
    if (req.method === "GET" || req.method === "HEAD") { // whoami
      const s = await authUser(req);
      if (!s) return json(401, { e: "unauthorized" });
      // D93: `exp` alimenta el contador de días restantes de la sesión
      return json(200, { uid: s.uid, u: s.username, did: s.did, exp: s.exp });
    }
    if (req.method !== "POST") return json(405, { e: "method" });
    const ct = req.headers.get("content-type") ?? "";
    if (!ct.includes("application/json")) return json(415, { e: "bad_content_type" });
    const body = await req.json().catch(() => null);
    if (!body || typeof body.a !== "string") return json(400, { e: "bad_json" });
    if (body.a === "reg") return await register(body);
    if (body.a === "in") return await login(body);
    if (body.a === "out") return await logout(req);
    // Acciones de sala: requieren sesión válida
    const me = await authUser(req);
    if (!me) return json(401, { e: "unauthorized" });
    if (body.a === "room_new") return await roomNew(me, body);
    if (body.a === "room_join") return await roomJoin(me, body);
    if (body.a === "room_leave") return await roomLeave(me, body);
    if (body.a === "rooms") return await roomList(me);
    if (body.a === "search") return await userSearch(me, body);
    if (body.a === "pk") return await uploadPub(me, body);
    if (body.a === "dm_new") return await dmNew(me, body);
    if (body.a === "dm_ack") return await dmAck(me, body);
    return json(400, { e: "bad_action" });
  } catch (e) {
    console.error("auth error:", e);
    return json(500, fail(e));
  }
});
