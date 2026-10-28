// D92: genera las bases 3D cuerpo completo de los avatares (image-to-image
// con la ilustración original como guía de estilo). Fondo verde para chroma.
import ZAI from 'z-ai-web-dev-sdk';
import fs from 'fs';

const REF = '/home/z/my-project/app/tools/avatares/reference/cerlita-koalita-ref.jpg';
const OUT = '/home/z/my-project/app/tools/avatares/work3d';

const JOBS = [
  {
    out: `${OUT}/raw_pig.png`,
    prompt:
      'Create a cute 3D render character of the PINK PIGGY from the reference image ' +
      '(same face, same pink colors, same rosy cheeks, same cute style): FULL BODY from ' +
      'head to feet, standing upright facing forward, smiling happily, arms relaxed at ' +
      'her sides, chubby rounded toddler body, tiny feet, no clothes. Soft rounded ' +
      'Pixar-style 3D toy look, matte clay finish, soft studio lighting, subtle soft ' +
      'shading. ENTIRE body visible with generous empty margins around it. Solid flat ' +
      'bright chroma-key green background (#00FF00), no shadows on the background, no ' +
      'text, no extra elements.',
  },
  {
    out: `${OUT}/raw_koala.png`,
    prompt:
      'Create a cute 3D render character of the GREY KOALA from the reference image ' +
      '(same face, same grey-blue fur color, same big round ears with cream inner ears, ' +
      'same cute style): FULL BODY from head to feet, standing upright facing forward, ' +
      'smiling happily, arms relaxed at his sides, chubby rounded toddler body, tiny ' +
      'feet, no clothes. Soft rounded Pixar-style 3D toy look, matte clay finish, soft ' +
      'studio lighting, subtle soft shading. ENTIRE body visible with generous empty ' +
      'margins around it. Solid flat bright chroma-key green background (#00FF00), no ' +
      'shadows on the background, no text, no extra elements.',
  },
  {
    out: `${OUT}/raw_pair.png`,
    prompt:
      'Create a cute 3D render of BOTH characters from the reference image together: ' +
      'the pink piggy and the grey koala, FULL BODY, standing side by side hugging ' +
      'each other, both smiling with closed happy eyes, chubby rounded toddler bodies, ' +
      'tiny feet. Soft rounded Pixar-style 3D toy look, matte clay finish, soft studio ' +
      'lighting. ENTIRE bodies visible with generous empty margins around. Solid flat ' +
      'bright chroma-key green background (#00FF00), no shadows on the background, no ' +
      'text, no extra elements.',
  },
];

const refB64 = fs.readFileSync(REF).toString('base64');
const zai = await ZAI.create();

for (const job of JOBS) {
  const t0 = Date.now();
  const resp = await zai.images.generations.edit({
    prompt: job.prompt,
    images: [{ url: `data:image/jpeg;base64,${refB64}` }],
    size: '1024x1024',
  });
  const b64 = resp?.data?.[0]?.base64;
  if (!b64) throw new Error('respuesta sin imagen: ' + JSON.stringify(resp).slice(0, 300));
  fs.writeFileSync(job.out, Buffer.from(b64, 'base64'));
  console.log(`OK ${job.out} (${Math.round((Date.now() - t0) / 1000)}s, ${Math.round(b64.length / 1024)} KB)`);
}
console.log('LISTO');
