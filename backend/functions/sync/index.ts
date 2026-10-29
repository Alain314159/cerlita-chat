// Edge Function: sync — GET incremental de mensajes desde un seq
// GET /functions/v1/sync?s=<seq>&l=<limit>  (Bearer token de sesión)
// 200 JSON compacto {n: next_seq, m: [[msg_id, room_id, sender_id, seq, nonce_b64,
//   ct_b64, sender_uname, ts_ms], ...], rd: [[room_id, delivered_seq], ...],
//   dm: [[room_id, from_uid, from_uname, from_pub_b64, wrapped_b64], ...]}
//   sender_uname: nombre del emisor — la UI lo muestra sin lookup adicional.
//   ts_ms:        coalesce(client_ts, created_at) en epoch ms — hora de muestra.
//   rd (v0.6.0, D68): por cada sala mía, el seq máximo que OTROS miembros ya
//   bajaron → el cliente marca sus mensajes ✓✓ «entregado». Si no hay mensajes
//   nuevos PERO sí entregas nuevas, se responde 200 con m: [].
//   dm (v0.7.0, D74): wraps pendientes de chats directos — la roomKey ENVUELTA
//   (X25519+HKDF+ChaCha, opaca aquí) + la pública del creador. Se sirve hasta
//   que el cliente acusa (auth/dm_ack).
// 204 si no hay nada nuevo NI entregas NI wraps (0 bytes de cuerpo)
// gzip cuando el cuerpo supera 1 KB (OkHttp lo descomprime solo)
// Membresía: solo mensajes de las rooms donde el usuario es miembro.
//
// ── S1/D106 (Q4 auditoría): TODO en UNA query ──
// Antes: 5 queries secuenciales por poll (sesión, mensajes, update de
// delivered, entregas de otros, wraps) — en una red de 500 ms de RTT eran
// ~2,5 s de siesta POR POLL, con el poll cada 15 s (y por timbre) en
// primer plano. Ahora es UN solo round-trip con CTEs:
//   sess  → valida el token y saca el uid (la «caché de sesión» de la
//           investigación resultó INNECESARIA: al ir en la misma query, la
//           validación no cuesta ningún round-trip extra y la revocación de
//           un token sigue siendo INMEDIATA — mejor aún que cachear 60 s).
//   served→ los mensajes nuevos (modo global o de UNA sala).
//   upd   → delivered_seq de mis salas a partir de lo servido (CTE de
//           modificación: Postgres garantiza que se ejecuta exactamente una
//           vez, se lea o no su salida).
//   rd    → entregas de otros en mis salas (mis ✓✓).
//   dmk   → wraps de chats directos pendientes (solo en modo global).
// El modo sala (?room=) comparte la MISMA query: memb distingue «no soy
// miembro» (403) de «no hay nada nuevo» (204).
import postgres from "npm:postgres@3.4.9";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const enc = new TextEncoder();
const DEBUG = Deno.env.get("DEBUG") === "1";
const json = (status: number, obj: unknown) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}
const fail = (e: unknown) => DEBUG ? { e: "internal", d: String(e) } : { e: "internal" };

// ── S1/D106: rate limiting blando por instancia cálida ──
// Tope de 40 sync/min por token: el polling activo (12/min) + timbre + red
// + alarma + worker caben de sobra; solo corta un bucle desbocado. Las Edge
// Functions pueden tener varias instancias → best-effort, no garantía.
const RL_WINDOW_MS = 60_000;
const RL_MAX = 40;
const rlMap = new Map<string, number[]>();
function allowRequest(hash: string): boolean {
  if (rlMap.size > 4096) rlMap.clear(); // techo de memoria (tokens rotados)
  const now = Date.now();
  const hist = (rlMap.get(hash) ?? []).filter((t) => now - t < RL_WINDOW_MS);
  if (hist.length >= RL_MAX) {
    rlMap.set(hash, hist);
    return false;
  }
  hist.push(now);
  rlMap.set(hash, hist);
  return true;
}

async function gzipBytes(data: Uint8Array): Promise<Uint8Array | null> {
  try {
    const cs = new CompressionStream("gzip");
    const stream = new Blob([data as BlobPart]).stream().pipeThrough(cs);
    return new Uint8Array(await new Response(stream).arrayBuffer());
  } catch { return null; } // sin CompressionStream → sin gzip, degradación limpia
}

Deno.serve(async (req) => {
  try {
    if (req.method !== "GET" && req.method !== "HEAD") return json(405, { e: "method" });
    const h = req.headers.get("authorization") ?? "";
    if (!h.startsWith("Bearer ")) return json(401, { e: "unauthorized" });
    const hash = toHex(sha256(enc.encode(h.slice(7))));

    if (!allowRequest(hash)) return json(429, { e: "rate_limited" });

    const url = new URL(req.url);
    const since = Math.max(0, parseInt(url.searchParams.get("s") ?? "0", 10) || 0);
    const limit = Math.min(200, Math.max(1, parseInt(url.searchParams.get("l") ?? "100", 10) || 100));

    // Modo SALA (B1 auditoría): recuperación del historial de UNA sala. El
    // cursor GLOBAL deja fuera los mensajes previos al join; sin este modo,
    // quien se une a una sala con historia jamás la ve.
    const roomParam = url.searchParams.get("room");
    if (roomParam && !/^[0-9a-f-]{36}$/i.test(roomParam)) return json(400, { e: "bad_room" });
    const room: string | null = roomParam ?? null;

    // ── LA query (D106): 1 round-trip para todo ──
    const [row] = await sql`
      with sess as (
        select s.user_id as uid from sessions s
        where s.token_hash = ${hash} and s.revoked_at is null and s.expires_at > now()
        limit 1
      ),
      memb as (
        select 1 as ok from room_members
        where ${room}::uuid is not null
          and room_id = ${room}::uuid
          and user_id = (select uid from sess)
        limit 1
      ),
      served as (
        select m.msg_id as id, m.room_id as rid, m.sender_id as sid, m.seq as seq,
               translate(encode(m.nonce, 'base64'), chr(10), '') as nb,
               translate(encode(m.ciphertext, 'base64'), chr(10), '') as cb,
               su.username as suname,
               (extract(epoch from coalesce(m.client_ts, m.created_at)) * 1000)::bigint as ts
        from messages m
        join users su on su.id = m.sender_id
        where m.seq > ${since}
          and m.room_id in (
            select room_id from room_members
            where user_id = (select uid from sess)
              and (${room}::uuid is null or room_id = ${room}::uuid)
          )
        order by m.seq asc
        limit ${limit}
      ),
      upd as (
        update room_members rm set delivered_seq = s.mx
        from (select rid as room_id, max(seq) as mx from served group by rid) s
        where rm.room_id = s.room_id
          and rm.user_id = (select uid from sess)
          and rm.delivered_seq < s.mx
        returning 1
      ),
      rd as (
        select rm.room_id as rid, max(rm.delivered_seq) as mx
        from room_members rm
        join room_members mine on mine.room_id = rm.room_id and mine.user_id = (select uid from sess)
        where rm.user_id <> (select uid from sess)
          and rm.delivered_seq > 0
          and ${room}::uuid is null
        group by rm.room_id
      ),
      dmk as (
        select k.room_id as rid, k.from_user as fuid, fu.username as funame,
               fu.pub as fpub, k.wrapped as w
        from dm_keys k join users fu on fu.id = k.from_user
        where k.for_user = (select uid from sess)
          and k.consumed_at is null
          and ${room}::uuid is null
        order by k.id limit 20
      )
      select (select uid from sess) as uid,
             (select count(*) from memb) as memb,
             (select coalesce(json_agg(json_build_array(id, rid, sid, seq, nb, cb, suname, ts)), '[]'::json)
                from served) as msgs,
             (select coalesce(json_agg(json_build_array(rid, mx)), '[]'::json)
                from rd) as rds,
             (select coalesce(json_agg(json_build_array(rid, fuid, funame, translate(encode(fpub, 'base64'), chr(10), ''), translate(encode(w, 'base64'), chr(10), ''))), '[]'::json)
                from dmk) as dms`;

    if (!row || row.uid == null) return json(401, { e: "unauthorized" });

    const msgs: any[][] = row.msgs ?? [];
    const rd: any[][] = (row.rds ?? []).map((x: any[]) => [x[0], Number(x[1])]);
    const dm: any[][] = row.dms ?? [];

    // modo sala: no miembro → 403 (membresía verificada en la misma query)
    if (room && Number(row.memb) === 0) return json(403, { e: "not_member" });

    if (msgs.length === 0) {
      if (room) return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
      if (rd.length === 0 && dm.length === 0) {
        return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
      }
      const empty = enc.encode(JSON.stringify({ n: since, m: [], ...(rd.length ? { rd } : {}), ...(dm.length ? { dm } : {}) }));
      return new Response(empty as BodyInit, {
        status: 200,
        headers: { "content-type": "application/json", "cache-control": "no-store" },
      });
    }

    const nextSeq = Number(msgs[msgs.length - 1][3]);
    const payload: Record<string, unknown> = { n: nextSeq, m: msgs };
    if (!room && rd.length > 0) payload.rd = rd;   // modo sala: como siempre, sin rd
    if (!room && dm.length > 0) payload.dm = dm;
    const body = enc.encode(JSON.stringify(payload));
    const headers: Record<string, string> = {
      "content-type": "application/json",
      "x-next-seq": String(nextSeq),
      "cache-control": "no-store",
    };
    if (body.length > 1024) {
      const gz = await gzipBytes(body);
      if (gz) {
        headers["content-encoding"] = "gzip";
        return new Response(gz as BodyInit, { status: 200, headers });
      }
    }
    return new Response(body as BodyInit, { status: 200, headers });
  } catch (e) {
    console.error("sync error:", e);
    return json(500, fail(e));
  }
});
