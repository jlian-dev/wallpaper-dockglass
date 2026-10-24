// Rode DEPOIS de "npx cap add android" e ANTES de "npx cap sync android".
// 1) copia o plugin nativo (sobreposição para alinhar a dock) e a MainActivity que o registra;
// 2) pede as permissões "Exibir sobre outros apps" e "Definir papel de parede" no AndroidManifest.
const fs = require('fs'), path = require('path');
const dir = 'android/app/src/main/java/com/dockliquida/app';   // pacote = appId do capacitor.config.json
fs.mkdirSync(dir, { recursive: true });
for (const f of ['MainActivity.java', 'DockOverlayPlugin.java', 'WallpaperRotationWorker.java']) {
  fs.copyFileSync(path.join('native', f), path.join(dir, f));
}
const mf = 'android/app/src/main/AndroidManifest.xml';
let m = fs.readFileSync(mf, 'utf8');
for (const perm of ['SYSTEM_ALERT_WINDOW', 'SET_WALLPAPER']) {   // sobreposição + aplicar papel de parede
  if (!m.includes(perm)) m = m.replace('</manifest>', '    <uses-permission android:name="android.permission.' + perm + '" />\n</manifest>');
}
fs.writeFileSync(mf, m);
// WorkManager (agendador do Android) para a troca automática de wallpaper
const gf = 'android/app/build.gradle';
let g = fs.readFileSync(gf, 'utf8');
if (!g.includes('androidx.work:work-runtime')) {
  g = g.replace(/dependencies\s*\{/, 'dependencies {\n    implementation "androidx.work:work-runtime:2.9.1"');
  fs.writeFileSync(gf, g);
}
console.log('OK: plugin DockOverlay e permissão aplicados');
