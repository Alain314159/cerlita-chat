// Edge Function: push — registra el token FCM de ESTA sesión (D121)
// POST /functions/v1/push  (Bearer token de sesión)
// Body: {t: "<fcm_token>"}     → asocia el token a la sesión actual
//       {t: ""}                → lo borra (app sin Play Services, logout local)
//
// Reglas:
//   * Un token FCM identifica UN dispositivo físico. Si aparece en otra
//     sesión (p.ej. re-login), se le quita a la vieja: Google cobra un
//     envío por token y duplicar notifica dos veces.
//   * La sesión que llama es la única que puede escribir SU fila.
//   * El token nunca se valida contra Google aquí (sería una ida extra):
//     si es basura, el outbox recibirá 404 de FCM al enviar y el error
//     se ignora (best-effort, igual que el timbre D63).
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

// Rango real de tokens FCM: ~140-250 chars. Holgura para crecer.
const TOKEN_MIN = 64;
const TOKEN_MAX = 512;

Deno.serve(async (req) => {
  try {
    if (req.method !== "POST") return json(405, { e: "method" });
    const h = req.headers.get("authorization") ?? "";
    if (!h.startsWith("Bearer ")) return json(401, { e: "unauthorized" });
    const hash = toHex(sha256(enc.encode(h.slice(7))));

    const [me] = await sql`
      select session_id from sessions
      where token_hash = ${hash} and revoked_at is null and expires_at > now()
      limit 1`;
    if (!me) return json(401, { e: "unauthorized" });

    const ctType = req.headers.get("content-type") ?? "";
    if (!ctType.includes("application/json")) return json(415, { e: "bad_content_type" });
    const body = await req.json().catch(() => null);
    if (!body || typeof body.t !== "string") return json(400, { e: "bad_request" });

    const t = body.t.trim();

    if (t === "") {
      // Borrado explícito (app sin Play Services / logout local)
      await sql`update sessions set fcm_token = null where token_hash = ${hash}`;
      if (DEBUG) console.log("push: token borrado");
      return json(200, { ok: true });
    }

    if (t.length < TOKEN_MIN || t.length > TOKEN_MAX) return json(400, { e: "bad_token" });

    // El token es de UN dispositivo: quitarlo de cualquier otra sesión que
    // lo tuviera (re-login, sesión vieja sin expirar) — una sola notificación.
    await sql`update sessions set fcm_token = null where fcm_token = ${t} and token_hash <> ${hash}`;

    await sql`update sessions set fcm_token = ${t} where token_hash = ${hash}`;
    await sql`update sessions set last_seen_at = now() where token_hash = ${hash}`;

    if (DEBUG) console.log("push: token registrado", t.length, "chars");
    return json(200, { ok: true });
  } catch (e) {
    console.error("push error:", e);
    return json(500, { e: "internal" });
  }
});
