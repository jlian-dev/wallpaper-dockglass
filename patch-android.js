// Rode DEPOIS de "npx cap add android" e ANTES de "npx cap sync android".
// 1) copia o plugin nativo (sobreposição para alinhar a dock) e a MainActivity que o registra;
// 2) pede a permissão "Exibir sobre outros apps" no AndroidManifest.
const fs = require('fs'), path = require('path');
const dir = 'android/app/src/main/java/com/dockliquida/app';   // pacote = appId do capacitor.config.json
fs.mkdirSync(dir, { recursive: true });
for (const f of ['MainActivity.java', 'DockOverlayPlugin.java']) {
  fs.copyFileSync(path.join('native', f), path.join(dir, f));
}
const mf = 'android/app/src/main/AndroidManifest.xml';
let m = fs.readFileSync(mf, 'utf8');
if (!m.includes('SYSTEM_ALERT_WINDOW')) {
  m = m.replace('</manifest>', '    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n</manifest>');
  fs.writeFileSync(mf, m);
}
console.log('OK: plugin DockOverlay e permissão aplicados');
