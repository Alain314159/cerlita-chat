// Edge Function: sync — GET incremental de mensajes desde un seq
// GET /functions/v1/sync?s=<seq>&l=<limit>  (Bearer token de sesión)
// 200 JSON compacto {n: next_seq, m: [[msg_id, room_id, sender_id, seq, nonce_b64,
//   ct_b64, sender_uname, ts_ms], ...]}
//   sender_uname: nombre del emisor — la UI lo muestra sin lookup adicional.
//                 (En el modelo rooms ya NO se usa para derivar convKey: el salt es
//                  roomId y la clave madre es roomKey — ver docs/DECISIONES.md D40/D41.)
//   ts_ms:        coalesce(client_ts, created_at) en epoch ms — hora de muestra.
// 204 si no hay nada nuevo (0 bytes de cuerpo)
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

    if (rows.length === 0) return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });

    const nextSeq = Number(rows[rows.length - 1].seq);
    const msgs = rows.map((r) => [
      r.msg_id, r.room_id, r.sender_id, Number(r.seq),
      b64(new Uint8Array(r.nonce)), b64(new Uint8Array(r.ciphertext)),
      r.suname, new Date(r.ts).getTime(),
    ]);
    const body = enc.encode(JSON.stringify({ n: nextSeq, m: msgs }));
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
