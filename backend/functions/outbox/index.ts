// Edge Function: outbox — POST lote de mensajes cifrados (idempotente)
// POST /functions/v1/outbox  (Bearer token de sesión)
// Body: {to: "<usuario destino>", m: [[msg_id, ct_b64, nonce_b64, ts_ms?], ...]} (máx 64)
// El msg_id (UUIDv7 del cliente) es PK: reintentos seguros por construcción.
import postgres from "npm:postgres@3.4.9";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const enc = new TextEncoder();
const DEBUG = Deno.env.get("DEBUG") === "1";
const json = (status: number, obj: unknown) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

const USERNAME_RE = /^[a-z0-9_]{3,24}$/;
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
    if (!body || typeof body.to !== "string") return json(400, { e: "bad_request" });
    const to = body.to.trim().toLowerCase();
    if (!USERNAME_RE.test(to)) return json(400, { e: "bad_to" });
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

    const [partner] = await sql`select id from users where username = ${to} limit 1`;
    if (!partner) return json(404, { e: "no_user" });
    const uid = String(me.uid), pid = String(partner.id);
    if (uid === pid) return json(400, { e: "self" });

    // Conversación 1-a-1 auto-creada con el par ordenado (idempotente)
    const [conv] = await sql`
      with ins as (
        insert into conversations (user_lo, user_hi)
        values (least(${uid}::uuid, ${pid}::uuid), greatest(${uid}::uuid, ${pid}::uuid))
        on conflict do nothing
        returning id
      )
      select id from ins
      union all
      select id from conversations
       where user_lo = least(${uid}::uuid, ${pid}::uuid) and user_hi = greatest(${uid}::uuid, ${pid}::uuid)
       limit 1`;
    if (!conv) return json(500, { e: "no_conv" });

    await sql`
      insert into messages ${
        sql(prepared.map((p) => ({
          msg_id: p.msg_id, conversation_id: conv.id, sender_id: uid,
          sender_device_id: me.did, ciphertext: p.ct, nonce: p.nonce, client_ts: p.ts,
        })), "msg_id", "conversation_id", "sender_id", "sender_device_id", "ciphertext", "nonce", "client_ts")
      } on conflict (msg_id) do nothing`;

    // Ack de los que existen (insertados ahora + aceptados en reintentos previos)
    const ids = prepared.map((p) => p.msg_id);
    const acked = await sql`
      select msg_id from messages
      where msg_id = any(${ids}::uuid[]) and conversation_id = ${conv.id} and sender_id = ${uid}`;

    await sql`update sessions set last_seen_at = now() where token_hash = ${hash}`;
    return json(200, { a: acked.map((r) => r.msg_id) });
  } catch (e) {
    console.error("outbox error:", e);
    return json(500, fail(e));
  }
});
