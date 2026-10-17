// Rode DEPOIS de "npx cap add android" e ANTES de "npx cap sync android".
// Torna a janela do app translúcida, para a tela inicial verdadeira aparecer por trás
// (usado pelo botão "Tela inicial ao vivo" do app).
const fs = require('fs');
const f = 'android/app/src/main/res/values/styles.xml';
let s = fs.readFileSync(f, 'utf8');
if (s.includes('windowIsTranslucent')) { console.log('já aplicado'); process.exit(0); }
const novo = s.replace(/(<style name="AppTheme\.NoActionBar"[^>]*>)/,
  '$1\n        <item name="android:windowIsTranslucent">true</item>\n        <item name="android:windowBackground">@android:color/transparent</item>');
if (novo === s) { console.error('Não achei AppTheme.NoActionBar em ' + f); process.exit(1); }
fs.writeFileSync(f, novo);
console.log('OK: tema translúcido aplicado');
