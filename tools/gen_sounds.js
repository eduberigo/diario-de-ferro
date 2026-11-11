// Gera os sons do app Android a partir dos mesmos sintetizadores da página (OfflineAudioContext),
// e deixa cada um mais denso (saturação suave + normalização) para soar mais alto no alto-falante do celular.
// Uso: python3 tools/build.py && node tools/gen_sounds.js   (precisa do Playwright; grava em android-app/.../res/raw)
// Mudou um sintetizador em src/app.html (beep, bell, START_SND, COUNT_SND...)? Rode de novo para o app Android tocar igual.
const path = require('path'), fs = require('fs'), ROOT = path.resolve(__dirname, '..');
let chromium; try { ({ chromium } = require('playwright')); } catch (e) { ({ chromium } = require('/opt/node22/lib/node_modules/playwright')); }
const OUT = process.argv[2] || path.join(ROOT, 'android-app/android/app/src/main/res/raw');
(async () => { const b = await chromium.launch(); const p = await b.newPage();
p.on('pageerror', e => { if (!/resume|InvalidStateError/.test(e.message)) console.log('ERR', e.message); });
await p.route('**/*', r => r.request().url().startsWith('file:') ? r.continue() : r.abort());
await p.goto('file://' + path.join(ROOT, 'index.html')); await p.waitForTimeout(200);
const keys = {bipe3: 'beep(3)', bipe2: 'beep(2)', bipe: 'beep(1)', sino: 'bell()', apito: 'whistle()',
  gongo: 'START_SND.gongo()', campainha: 'START_SND.campainha()', buzina: 'START_SND.buzina()', arpejo: 'START_SND.arpejo()', tambor: 'START_SND.tambor()', vai: 'START_SND.vai()',
  c_bipe_a: 'tickSnd()', c_bipe_b: 'tickSnd()',
  c_madeira_a: 'COUNT_SND.madeira(3)', c_madeira_b: 'COUNT_SND.madeira(1)', c_grave_a: 'COUNT_SND.grave(3)', c_grave_b: 'COUNT_SND.grave(1)',
  c_digital_a: 'COUNT_SND.digital(3)', c_digital_b: 'COUNT_SND.digital(1)', c_duplo_a: 'COUNT_SND.duplo(3)', c_duplo_b: 'COUNT_SND.duplo(1)',
  c_pulso_a: 'COUNT_SND.pulso(3)', c_pulso_b: 'COUNT_SND.pulso(1)'};
const DRIVE = 2.6;
for (const [k, expr] of Object.entries(keys)) {
  const r = await p.evaluate(async ([expr, DRIVE]) => {
    S.settings.sound = true; const sr = 44100, off = new OfflineAudioContext(1, sr * 4, sr);
    audio = off; outNode = null; window.holdAudio = () => {}; window.natDuck = () => {};
    // saída direta (sem o ganho/compressor da página): o tratamento de volume é feito abaixo, igual para todos
    window.outN = () => off.destination;
    try { eval(expr); } catch (e) { return {err: e.message}; }
    const buf = await off.startRendering(); audio = null; outNode = null;
    const d = buf.getChannelData(0); let end = d.length; while (end > 0 && Math.abs(d[end - 1]) < 2e-4) end--; end = Math.min(d.length, end + Math.round(sr * .04));
    let pk = 0; for (let i = 0; i < end; i++) pk = Math.max(pk, Math.abs(d[i])); if (!pk) return {err: 'silêncio'};
    const rms = a => { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * a[i]; return Math.sqrt(s / a.length); };
    const x = new Float32Array(end); for (let i = 0; i < end; i++) x[i] = d[i] / pk;           // normalizado (como era)
    const y = new Float32Array(end), tg = Math.tanh(DRIVE); for (let i = 0; i < end; i++) y[i] = Math.tanh(DRIVE * x[i]) / tg;   // saturação suave
    for (let i = 0, n = Math.min(end, Math.round(sr * .003)); i < n; i++) { y[i] *= i / n; }      // evita clique no início
    for (let i = 0, n = Math.min(end, Math.round(sr * .01)); i < n; i++) { y[end - 1 - i] *= i / n; }
    const out = y, n = end, ab = new ArrayBuffer(44 + n * 2), v = new DataView(ab), ws = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
    ws(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); ws(8, 'WAVE'); ws(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); ws(36, 'data'); v.setUint32(40, n * 2, true);
    for (let i = 0; i < n; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, out[i] * .98)) * 32767, true);
    let s = ''; const u = new Uint8Array(ab); for (let i = 0; i < u.length; i += 8192) s += String.fromCharCode(...u.subarray(i, i + 8192));
    return {b64: btoa(s), dur: n / sr, db0: 20 * Math.log10(rms(x) * .97), db1: 20 * Math.log10(rms(y) * .98)};
  }, [expr, DRIVE]);
  if (r.err) { console.log(k, 'ERRO', r.err); continue; }
  fs.writeFileSync(OUT + '/ferro_s_' + k + '.wav', Buffer.from(r.b64, 'base64'));
  console.log(k.padEnd(12), (r.dur * 1000).toFixed(0).padStart(5) + ' ms', ' antes ' + r.db0.toFixed(1) + ' dB', ' depois ' + r.db1.toFixed(1) + ' dB', ' ganho +' + (r.db1 - r.db0).toFixed(1) + ' dB');
}
await b.close(); })();
