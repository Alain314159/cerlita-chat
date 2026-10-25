// Edge Function: heartbeat — latido anti-pausa para el plan Free
// GET /functions/v1/heartbeat → {ok: true, t: <último latido>}
// Diseñado para ser llamado por un cron externo (p. ej. cron-job.org cada 3 días).
import postgres from "npm:postgres@3.4.9";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL") ?? "", {
  prepare: false, max: 1, idle_timeout: 15, connect_timeout: 8,
});

const DEBUG = Deno.env.get("DEBUG") === "1";

Deno.serve(async () => {
  try {
    await sql`update app_heartbeat set last_beat = now() where id = 1`;
    const [row] = await sql`select last_beat from app_heartbeat where id = 1`;
    return new Response(JSON.stringify({ ok: true, t: row?.last_beat ?? null }), {
      status: 200,
      headers: { "content-type": "application/json", "cache-control": "no-store" },
    });
  } catch (e) {
    console.error("heartbeat error:", e);
    return new Response(JSON.stringify({ ok: false, ...(DEBUG ? { d: String(e) } : {}) }), {
      status: 500,
      headers: { "content-type": "application/json" },
    });
  }
});
