// Debug del handshake Phoenix de Supabase Realtime
import { webcrypto as crypto } from "node:crypto";
import { readFileSync } from "node:fs";

// leer secrets
const envFile = readFileSync("/home/z/my-project/secrets/supabase.env", "utf8");
const env = Object.fromEntries(
  envFile.split("\n").filter((l) => l.includes("=")).map((l) => {
    const i = l.indexOf("=");
    return [l.slice(0, i).replace("export ", "").trim(), l.slice(i + 1).trim()];
  }),
);

const ANON = env.SUPA_ANON_KEY;
const HOST = env.SUPA_URL.replace(/^https:\/\//, "").replace(/\/.*$/, "");

async function topicHex(roomId) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(roomId), "HKDF", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: enc.encode("cerlita-rt-v1"), info: enc.encode("realtime-topic") },
    key, 128,
  );
  return [...new Uint8Array(bits)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const roomId = crypto.randomUUID();
const topic = `cerlita:room:${await topicHex(roomId)}`;
console.log("tema:", topic);

const ws = new WebSocket(`wss://${HOST}/realtime/v1/websocket?apikey=${ANON}&vsn=1.0.0`);
ws.onopen = () => {
  console.log("[abierto] enviando phx_join…");
  ws.send(JSON.stringify({
    topic, event: "phx_join", ref: "1",
    payload: { config: { broadcast: { ack: false, self: false }, presence: { key: "" }, postgres_changes: [] } },
  }));
};
ws.onmessage = (ev) => console.log("[frame]", String(ev.data).slice(0, 300));
ws.onerror = (e) => console.log("[error]", e.message ?? e);
ws.onclose = (e) => console.log("[cerrado]", e.code, e.reason);
setTimeout(() => process.exit(0), 10000);
