// Edge Function: sync — GET incremental de mensajes desde un seq
// GET /functions/v1/sync?s=<seq>&l=<limit>  (Bearer token de sesión)
// 200 JSON compacto {n: next_seq, m: [[msg_id, room_id, sender_id, seq, nonce_b64,
//   ct_b64, sender_uname, ts_ms], ...], rd: [[room_id, delivered_seq], ...],
//   dm: [[room_id, from_uid, from_uname, from_pub_b64, wrapped_b64], ...]}
//   sender_uname: nombre del emisor — la UI lo muestra sin lookup adicional.
//                 (En el modelo rooms ya NO se usa para derivar convKey: el salt es
//                  roomId y la clave madre es roomKey — ver docs/DECISIONES.md D40/D41.)
//   ts_ms:        coalesce(client_ts, created_at) en epoch ms — hora de muestra.
//   rd (v0.6.0, D68): por cada sala mía, el seq máximo que OTROS miembros ya
//   bajaron → el cliente marca sus mensajes ✓✓ «entregado». Metadato que el
//   servidor ya conocía (quién sincronizó qué). Si no hay mensajes nuevos PERO
//   sí entregas nuevas, se responde 200 con m: [] (el cliente viejo lo ignora).
//   dm (v0.7.0, D74): wraps pendientes de chats directos — la roomKey ENVUELTA
//   (X25519+HKDF+ChaCha, opaca aquí) + la pública del creador para poder
//   desenveloparla. Se sirve hasta que el cliente acusa (auth/dm_ack).
// 204 si no hay nada nuevo NI entregas NI wraps (0 bytes de cuerpo)
// gzip cuando el cuerpo supera 1 KB (OkHttp lo descomprime solo)
// Membresía: solo mensajes de las rooms donde el usuario es miembro.
import postgres from "npm:postgres@3.4.9";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const enc = new TextEncoder();
const DEBUG = Deno.env.get("DEBUG") === "1";
const json = (status: number, obj: unknown) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

function b64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}
const fail = (e: unknown) => DEBUG ? { e: "internal", d: String(e) } : { e: "internal" };

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

    const [me] = await sql`
      select s.user_id as uid from sessions s
      where s.token_hash = ${hash} and s.revoked_at is null and s.expires_at > now()
      limit 1`;
    if (!me) return json(401, { e: "unauthorized" });

    const url = new URL(req.url);
    const since = Math.max(0, parseInt(url.searchParams.get("s") ?? "0", 10) || 0);
    const limit = Math.min(200, Math.max(1, parseInt(url.searchParams.get("l") ?? "100", 10) || 100));

    // ── Modo SALA (B1 auditoría): recuperación del historial de UNA sala ──
    // GET /sync?room=<roomId>&s=<seq_sala> → SOLO esa sala, seq > s, mismo
    // formato {n, m}. El cursor GLOBAL deja fuera los mensajes previos al
    // join: sin este modo, quien se une a una sala con historia jamás la ve
    // (ni el nombre heredado del room_info, D69). Membresía verificada igual.
    const roomParam = url.searchParams.get("room");
    if (roomParam) {
      if (!/^[0-9a-f-]{36}$/i.test(roomParam)) return json(400, { e: "bad_room" });
      const member = await sql`select 1 from room_members
        where room_id = ${roomParam}::uuid and user_id = ${me.uid}::uuid limit 1`;
      if (member.length === 0) return json(403, { e: "not_member" });
      const rrows = await sql`
        select m.msg_id, m.room_id, m.sender_id, m.nonce, m.ciphertext, m.seq,
               su.username as suname, coalesce(m.client_ts, m.created_at) as ts
        from messages m
        join users su on su.id = m.sender_id
        where m.room_id = ${roomParam}::uuid and m.seq > ${since}
        order by m.seq asc
        limit ${limit}`;
      if (rrows.length === 0) return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
      const rids = rrows.map((r) => r.msg_id);
      await sql`
        update room_members rm set delivered_seq = s.mx
        from (
          select room_id, max(seq) as mx from messages
          where msg_id = any(${rids}::uuid[])
          group by room_id
        ) s
        where rm.room_id = s.room_id and rm.user_id = ${me.uid}::uuid
          and rm.delivered_seq < s.mx`;
      const rmsgs = rrows.map((r) => [
        r.msg_id, r.room_id, r.sender_id, Number(r.seq),
        b64(new Uint8Array(r.nonce)), b64(new Uint8Array(r.ciphertext)),
        r.suname, new Date(r.ts).getTime(),
      ]);
      const rnext = Number(rrows[rrows.length - 1].seq);
      const rbody = enc.encode(JSON.stringify({ n: rnext, m: rmsgs }));
      return new Response(rbody as BodyInit, {
        status: 200,
        headers: { "content-type": "application/json", "cache-control": "no-store", "x-next-seq": String(rnext) },
      });
    }

    const rows = await sql`
      select m.msg_id, m.room_id, m.sender_id, m.nonce, m.ciphertext, m.seq,
             su.username as suname, coalesce(m.client_ts, m.created_at) as ts
      from messages m
      join users su on su.id = m.sender_id
      where m.room_id in (
            select room_id from room_members where user_id = ${me.uid})
        and m.seq > ${since}
      order by m.seq asc
      limit ${limit}`;

    // ── Recibos de entrega (D68): registrar lo que ESTE usuario ya bajó ──
    // delivered_seq(room, member) = seq global máximo servido en este ciclo.
    // Una sola query (mismo patrón any() del outbox) sobre los msg_id servidos.
    if (rows.length > 0) {
      const ids = rows.map((r) => r.msg_id);
      await sql`
        update room_members rm set delivered_seq = s.mx
        from (
          select room_id, max(seq) as mx from messages
          where msg_id = any(${ids}::uuid[])
          group by room_id
        ) s
        where rm.room_id = s.room_id and rm.user_id = ${me.uid}::uuid
          and rm.delivered_seq < s.mx`;
    }

    // Entregas de OTROS en mis salas (para mis ✓✓). Se calcula SIEMPRE:
    // puede haber entregas nuevas aunque yo no tenga mensajes pendientes.
    const rdRows = await sql`
      select rm.room_id as rid, max(rm.delivered_seq) as mx
      from room_members rm
      join room_members mine on mine.room_id = rm.room_id and mine.user_id = ${me.uid}::uuid
      where rm.user_id <> ${me.uid}::uuid and rm.delivered_seq > 0
      group by rm.room_id`;
    const rd = rdRows.map((x: any) => [x.rid, Number(x.mx)]);

    // Chats directos pendientes de recoger (D74): wrap opaco + pública del
    // creador. Se sirven hasta el dm_ack del cliente (si la respuesta se
    // pierde, el próximo sync las vuelve a servir — no se pierde nada).
    const dmRows = await sql`
      select k.room_id as rid, k.from_user as fuid, fu.username as funame,
             fu.pub as fpub, k.wrapped as w
      from dm_keys k join users fu on fu.id = k.from_user
      where k.for_user = ${me.uid}::uuid and k.consumed_at is null
      order by k.id limit 20`;
    const dm = dmRows.map((x: any) => [
      x.rid, String(x.fuid), x.funame,
      x.fpub ? b64(new Uint8Array(x.fpub)) : null,
      b64(new Uint8Array(x.w)),
    ]);

    if (rows.length === 0) {
      // Sin mensajes nuevos: 204 solo si tampoco hay entregas ni wraps.
      if (rd.length === 0 && dm.length === 0) {
        return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
      }
      const empty = enc.encode(JSON.stringify({ n: since, m: [], ...(rd.length ? { rd } : {}), ...(dm.length ? { dm } : {}) }));
      return new Response(empty as BodyInit, {
        status: 200,
        headers: { "content-type": "application/json", "cache-control": "no-store" },
      });
    }

    const nextSeq = Number(rows[rows.length - 1].seq);
    const msgs = rows.map((r) => [
      r.msg_id, r.room_id, r.sender_id, Number(r.seq),
      b64(new Uint8Array(r.nonce)), b64(new Uint8Array(r.ciphertext)),
      r.suname, new Date(r.ts).getTime(),
    ]);
    const payload: Record<string, unknown> = { n: nextSeq, m: msgs };
    if (rd.length > 0) payload.rd = rd;
    if (dm.length > 0) payload.dm = dm;
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
