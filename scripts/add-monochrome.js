// Ícone temático (Material You / Android 13+): acrescenta a camada <monochrome> ao ícone adaptativo.
// Rode DEPOIS de qualquer passo que gere os ícones e ANTES do Gradle (passo "Compilar"). Pode rodar várias vezes.
//
// A camada monocromática é uma silhueta de uma cor só: o Android usa só a transparência dela e pinta com as cores do tema
// do usuário. Se existir assets/icon-monochrome.png (silhueta em PNG transparente, quadrada, ex.: 1024x1024), ela é usada;
// senão, usa um desenho padrão (celular com a Dock embaixo), em vetor.
const fs = require('fs'), path = require('path');
const RES = 'android/app/src/main/res';
if (!fs.existsSync(RES)) throw new Error('Não achei ' + RES + ' (rode depois de "npx cap add android").');

// 1) o desenho da camada
const PNG = 'assets/icon-monochrome.png', usaPng = fs.existsSync(PNG);
const XML_DRAWABLE = path.join(RES, 'drawable', 'ic_launcher_monochrome.xml');
const PNG_DRAWABLE = path.join(RES, 'drawable-nodpi', 'ic_launcher_monochrome.png');
fs.rmSync(XML_DRAWABLE, { force: true }); fs.rmSync(PNG_DRAWABLE, { force: true });   // evita dois recursos com o mesmo nome
if (usaPng) {
  fs.mkdirSync(path.dirname(PNG_DRAWABLE), { recursive: true });
  fs.copyFileSync(PNG, PNG_DRAWABLE);
  console.log('monochrome: usando ' + PNG);
} else {
  // Vetor 108x108 dp. Tudo dentro da "zona segura" (círculo de ~66 dp no centro): contorno do celular + a barra da Dock.
  fs.mkdirSync(path.dirname(XML_DRAWABLE), { recursive: true });
  fs.writeFileSync(XML_DRAWABLE, `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- contorno do celular (retângulo arredondado menos o miolo) -->
    <path android:fillColor="#FF000000" android:fillType="evenOdd"
        android:pathData="M44,27 H64 A7,7 0 0 1 71,34 V74 A7,7 0 0 1 64,81 H44 A7,7 0 0 1 37,74 V34 A7,7 0 0 1 44,27 Z M44,30.5 A3.5,3.5 0 0 0 40.5,34 V74 A3.5,3.5 0 0 0 44,77.5 H64 A3.5,3.5 0 0 0 67.5,74 V34 A3.5,3.5 0 0 0 64,30.5 Z"/>
    <!-- a Dock: barra arredondada na parte de baixo da tela -->
    <path android:fillColor="#FF000000"
        android:pathData="M46.5,67.5 H61.5 A2.5,2.5 0 0 1 64,70 A2.5,2.5 0 0 1 61.5,72.5 H46.5 A2.5,2.5 0 0 1 44,70 A2.5,2.5 0 0 1 46.5,67.5 Z"/>
</vector>
`);
  console.log('monochrome: usando o desenho padrão (vetor)');
}

// 2) liga a camada ao ícone adaptativo (normal e redondo)
const temForeground = fs.readdirSync(RES).some(d => /^(mipmap|drawable)/.test(d) &&
  fs.readdirSync(path.join(RES, d)).some(f => f.startsWith('ic_launcher_foreground')));
for (const nome of ['ic_launcher', 'ic_launcher_round']) {
  const dir = path.join(RES, 'mipmap-anydpi-v26'), f = path.join(dir, nome + '.xml');
  if (fs.existsSync(f)) {
    let x = fs.readFileSync(f, 'utf8');
    x = x.replace(/[ \t]*<monochrome[\s\S]*?(?:\/>|<\/monochrome>)\s*/g, '');                        // tira a camada anterior (roda quantas vezes quiser)
    // O @capacitor/assets encaixa o foreground com um <inset> (ex.: 16.7%) para a arte inteira caber na área visível.
    // Uma arte PNG própria segue a mesma regra do ícone normal; o desenho vetorial padrão já nasce dentro da zona segura.
    const inset = (x.match(/<foreground>\s*<inset[^>]*android:inset="([^"]+)"/) || [])[1];
    const camada = (usaPng && inset)
      ? `    <monochrome>\n        <inset android:drawable="@drawable/ic_launcher_monochrome" android:inset="${inset}" />\n    </monochrome>\n`
      : '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n';
    fs.writeFileSync(f, x.replace('</adaptive-icon>', camada + '</adaptive-icon>'));
    console.log('monochrome: ligado em ' + f);
  } else if (temForeground) {                                                                 // não existia: cria um completo
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(f, `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
`);
    console.log('monochrome: criado ' + f);
  } else console.warn('monochrome: sem ícone adaptativo em ' + f + ' e sem foreground; nada a fazer');
}
