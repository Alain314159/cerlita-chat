// Edge Function: auth — registro / login / logout / whoami
// Auth propia con token de sesión. La frase E2EE NUNCA llega aquí.
// Registro protegido por código de invitación (secret REG_INVITE_CODE).
import postgres from "npm:postgres@3.4.9";
import { argon2id } from "@noble/hashes/argon2.js";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const ARGON = { t: 3, m: 32768, p: 1, dkLen: 32 }; // pass del login; la frase E2EE se deriva SOLO en el cliente con m=64MiB
const USERNAME_RE = /^[a-z0-9_]{3,24}$/;
const DEVICE_RE = /^[0-9a-f-]{36}$/i;
const MAX_USERS = 10;

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
    select s.user_id as uid, s.device_id as did, u.username
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
  if (!USERNAME_RE.test(u)) return json(400, { e: "bad_username" });
  if (p.length < 8 || p.length > 512) return json(400, { e: "bad_password" });
  if (!DEVICE_RE.test(d)) return json(400, { e: "bad_device" });
  const [{ n }] = await sql`select count(*)::int as n from users`;
  if (n >= MAX_USERS) return json(403, { e: "user_cap" });

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = argon2id(enc.encode(p), salt, ARGON);
  const phc = `$argon2id$v=19$m=${ARGON.m},t=${ARGON.t},p=${ARGON.p}$${b64(salt)}$${b64(hash)}`;
  try {
    const [row] = await sql`insert into users (username, pass_hash) values (${u}, ${phc}) returning id`;
    const s = await newSession(row.id, d);
    return json(201, { uid: row.id, u, tok: s.tok, exp: s.exp });
  } catch (e: any) {
    if (String(e?.code) === "23505") return json(409, { e: "username_taken" });
    throw e;
  }
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

// ---------- router ----------
Deno.serve(async (req) => {
  try {
    if (req.method === "GET" || req.method === "HEAD") { // whoami
      const s = await authUser(req);
      if (!s) return json(401, { e: "unauthorized" });
      return json(200, { uid: s.uid, u: s.username, did: s.did });
    }
    if (req.method !== "POST") return json(405, { e: "method" });
    const ct = req.headers.get("content-type") ?? "";
    if (!ct.includes("application/json")) return json(415, { e: "bad_content_type" });
    const body = await req.json().catch(() => null);
    if (!body || typeof body.a !== "string") return json(400, { e: "bad_json" });
    if (body.a === "reg") return await register(body);
    if (body.a === "in") return await login(body);
    if (body.a === "out") return await logout(req);
    return json(400, { e: "bad_action" });
  } catch (e) {
    console.error("auth error:", e);
    return json(500, fail(e));
  }
});
