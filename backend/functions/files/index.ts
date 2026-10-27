// Edge Function: files — URL firmada de DESCARGA de adjuntos E2EE (v0.7.0, D76)
//
// POST /functions/v1/files  (Bearer token de sesión)  body JSON:
//   {a:"urlDl", p:"<room>/<uuid>.<ext>"}  → 200 {u:"https://…"}
//       URL firmada de descarga (10 min), SOLO para miembros de la sala
//       que dice el propio path (la sala no se pide por parámetro: el path
//       manda — no se puede pedir la URL de una sala ajena).
//
// La SUBIDA no pasa por aquí: el cliente hace PUT directo a Storage con la
// anon key (política RLS «attachments_insert_by_room_capability»: solo
// <roomId>/<uuid>.<ext>, y conocer un roomId ya ES la capability de la sala).
// Los bytes viajan CIFRADOS (ChaCha20-Poly1305); la clave, el nombre real,
// el mime y el tamaño viajan E2EE dentro del mensaje de chat (\u0000A).
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
const EXT_RE = /^[a-z0-9]{1,8}$/;
const BUCKET = "attachments";
const SIGN_TTL = 600; // segundos

function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}
const fail = (e: unknown) => DEBUG ? { e: "internal", d: String(e) } : { e: "internal" };

/** Path de Storage: <room>/<uuid>.<ext> — sin directorios raros, sin "..". */
function parsePath(p: unknown): { room: string; rest: string } | null {
  if (typeof p !== "string" || p.length > 100) return null;
  const segs = p.split("/");
  if (segs.length !== 2) return null;
  const room = segs[0].toLowerCase();
  if (!UUID_RE.test(room)) return null;
  const file = segs[1];
  const dot = file.lastIndexOf(".");
  if (dot < 1) return null;
  const name = file.slice(0, dot);
  const ext = file.slice(dot + 1).toLowerCase();
  if (!/^[a-z0-9-]{1,40}$/i.test(name) || !EXT_RE.test(ext)) return null;
  return { room, rest: `${name}.${ext}` };
}

Deno.serve(async (req) => {
  try {
    if (req.method !== "POST") return json(405, { e: "method" });
    const h = req.headers.get("authorization") ?? "";
    if (!h.startsWith("Bearer ")) return json(401, { e: "unauthorized" });
    const hash = toHex(sha256(enc.encode(h.slice(7))));
    const [me] = await sql`
      select s.user_id as uid from sessions s
      where s.token_hash = ${hash} and s.revoked_at is null and s.expires_at > now()
      limit 1`;
    if (!me) return json(401, { e: "unauthorized" });

    const ctType = req.headers.get("content-type") ?? "";
    if (!ctType.includes("application/json")) return json(415, { e: "bad_content_type" });
    const body = await req.json().catch(() => null);
    if (!body || typeof body.a !== "string") return json(400, { e: "bad_json" });

    const surl = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
    const skey = Deno.env.get("SUPA_SVC_JWT") ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""; // Storage exige JWT clásico (hallazgo 17)
    if (!surl || !skey) return json(500, { e: "no_storage_config" });

    if (body.a !== "urlDl") return json(400, { e: "bad_action" });
    const parsed = parsePath(body.p);
    if (!parsed) return json(400, { e: "bad_path" });
    const [member] = await sql`
      select 1 as ok from room_members
      where room_id = ${parsed.room}::uuid and user_id = ${me.uid}::uuid limit 1`;
    if (!member) return json(404, { e: "no_room" });
    const path = `${parsed.room}/${parsed.rest}`;

    const r = await fetch(`${surl}/storage/v1/object/sign/${BUCKET}/${path}`, {
      method: "POST",
      headers: { "Authorization": `Bearer ${skey}`, "Content-Type": "application/json" },
      body: JSON.stringify({ expiresIn: SIGN_TTL }),
    });
    const signBody = await r.text();
    if (!r.ok) {
      console.error("storage sign error:", r.status, signBody.slice(0, 200));
      return json(502, DEBUG ? { e: "storage_error", d: `http_${r.status} ${signBody.slice(0, 160)}` } : { e: "storage_error" });
    }
    let res: any = {};
    try { res = JSON.parse(signBody); } catch { /* abajo lo reporta */ }
    const rawUrl: string = res.signedURL ?? res.url ?? "";
    if (!rawUrl) return json(502, DEBUG ? { e: "storage_error", d: `sin_url ${signBody.slice(0, 200)}` } : { e: "storage_error" });
    // relativo al ROOT de Storage (/object/sign/...) → absolutizar con /storage/v1
    const abs = rawUrl.startsWith("http")
      ? rawUrl
      : surl + (rawUrl.startsWith("/storage/") ? "" : "/storage/v1") + rawUrl;
    await sql`update sessions set last_seen_at = now() where token_hash = ${hash}`;
    return json(200, { u: abs });
  } catch (e) {
    console.error("files error:", e);
    return json(500, fail(e));
  }
});
