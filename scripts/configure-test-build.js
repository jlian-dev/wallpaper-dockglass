/**
 * Configura APENAS o Android temporário gerado pelo GitHub Actions.
 * Não altera o identificador do Dock Líquida oficial no repositório.
 * Execute depois de `npx cap sync android`, antes de `assembleRelease`.
 */
const fs = require('node:fs');

const gradleFile = 'android/app/build.gradle';
const stringsFile = 'android/app/src/main/res/values/strings.xml';
const runNumber = Number.parseInt(process.env.GITHUB_RUN_NUMBER || '1', 10);
if (!Number.isSafeInteger(runNumber) || runNumber < 1 || runNumber > 2100000000) {
  throw new Error('GITHUB_RUN_NUMBER inválido para versionCode');
}

let gradle = fs.readFileSync(gradleFile, 'utf8');
// Muda somente o applicationId instalado, NÃO a namespace/Java original dos plugins.
const idPattern = /\bapplicationId\s+["']com\.dockliquida\.app["']/;
if (!idPattern.test(gradle)) {
  throw new Error('Não encontrei applicationId original no android/app/build.gradle');
}
gradle = gradle.replace(idPattern, 'applicationId "com.dockliquida.app.teste"');

const codePattern = /\bversionCode\s+\d+/;
const namePattern = /\bversionName\s+["'][^"']*["']/;
if (!codePattern.test(gradle) || !namePattern.test(gradle)) {
  throw new Error('Não encontrei versionCode/versionName no Gradle');
}
gradle = gradle.replace(codePattern, `versionCode ${runNumber}`);
gradle = gradle.replace(namePattern, `versionName "teste.${runNumber}"`);
fs.writeFileSync(gradleFile, gradle);

// Identifica visualmente a edição de teste sem mexer no pacote Java.
let strings = fs.readFileSync(stringsFile, 'utf8');
let count = 0;
for (const key of ['app_name', 'title_activity_main']) {
  const re = new RegExp(`(<string\\s+name=["']${key}["'][^>]*>)[\\s\\S]*?(<\\/string>)`);
  if (re.test(strings)) {
    strings = strings.replace(re, '$1Dock Líquida TESTE$2');
    count++;
  }
}
if (!count) throw new Error('Não encontrei o nome do app no strings.xml');
fs.writeFileSync(stringsFile, strings);
console.log(`APK de teste: com.dockliquida.app.teste | Dock Líquida TESTE | versão teste.${runNumber}`);
