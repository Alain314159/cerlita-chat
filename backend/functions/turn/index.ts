// Edge Function: turn — ICE servers para las llamadas WebRTC (B6/R3 auditoría)
// GET /functions/v1/turn  (Bearer token de sesión) → 200 {s:[{u,un?,pw?}], ttl}
//
// POR QUÉ: con solo STUN, dos teléfonos tras NAT simétrico (típico en redes
// móviles cubanas) no obtienen ruta directa y la llamada muere en
// «Conectando…». TURN es un relé de paquetes: el AUDIO/VÍDEO sigue cifrado
// DTLS-SRTP de extremo a extremo — el relay transporta bytes opacos, no
// puede leer ni modificar nada.
//
// Configuración por env (Management API → secrets):
//   TURN_URLS  — lista separada por comas: turn:host:puerto[?transport=tcp]
//   TURN_USER  — usuario TURN (opcional: STUN no necesita credenciales)
//   TURN_PASS  — contraseña TURN
// Sin TURN_* → solo STUN (comportamiento v0.7.x: la llamada funciona en red
// «fácil» y muere en NAT simétrico). Con TURN_* → STUN + TURN.
//
// Defaults del repo (scripts/deploy_functions.py): OpenRelay (Metered) free
// tier — 20 GB/mes, credenciales PÚBLICAS de prueba publicadas por Metered.
// Para producción personal: ExpressTURN/Cloudflare TURN (1000 GB/mes gratis)
// → cambiar los 3 env y re-desplegar. Las credenciales NUNCA van al APK:
// se sirven por aquí, con sesión válida.
import postgres from "npm:postgres@3.4.9";
import { sha256 } from "@noble/hashes/sha2.js";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const enc = new TextEncoder();
const json = (status: number, obj: unknown) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

const STUN = [
  "stun:stun.l.google.com:19302",
  "stun:stun1.l.google.com:19302",
  "stun:stun.cloudflare.com:3478",
];

Deno.serve(async (req) => {
  try {
    if (req.method !== "GET" && req.method !== "HEAD") return json(405, { e: "method" });
    const h = req.headers.get("authorization") ?? "";
    if (!h.startsWith("Bearer ")) return json(401, { e: "unauthorized" });
    const hash = toHex(sha256(enc.encode(h.slice(7))));

    const me = await sql`
      select s.user_id as uid from sessions s
      where s.token_hash = ${hash} and s.revoked_at is null and s.expires_at > now()
      limit 1`;
    if (!me || me.length === 0) return json(401, { e: "unauthorized" });

    const urls = (Deno.env.get("TURN_URLS") ?? "")
      .split(",").map((s) => s.trim()).filter(Boolean).filter((u) => u.toLowerCase().startsWith("turn:"));
    const user = Deno.env.get("TURN_USER") ?? "";
    const pass = Deno.env.get("TURN_PASS") ?? "";

    const servers: Array<{ u: string; un?: string; pw?: string }> =
      STUN.map((u) => ({ u }));
    for (const u of urls) {
      // sin usuario/clave configurados, un turn: sin credenciales no sirve de
      // nada en la práctica — se omite para no gastar tiempo de ICE en él
      if (user && pass) servers.push({ u, un: user, pw: pass });
    }

    return json(200, { s: servers, ttl: 600 }); // el cliente cachea 10 min
  } catch (e) {
    console.error("turn error:", e);
    return json(500, { e: "internal" });
  }
});
