# Dock Líquida: como gerar o APK

O app (www/index.html) é empacotado com o Capacitor, que o coloca dentro de um WebView Android.
No APK, o botão "Baixar PNG" abre o menu Compartilhar (Fotos, Galeria, Arquivos...), porque downloads comuns não funcionam em WebView.

## Opção A: na nuvem, sem Android Studio (recomendada)
1. Crie um repositório no GitHub e envie TODOS os arquivos desta pasta, incluindo `.github/workflows/apk.yml`.
   (Pelo site: "Add file > Upload files". Se a pasta oculta .github não subir, use "Add file > Create new file",
   digite `.github/workflows/apk.yml` no nome e cole o conteúdo do arquivo.)
2. Abra a aba **Actions**, escolha **Gerar APK** e clique em **Run workflow** (também roda sozinho a cada push).
3. Quando terminar (uns 5 a 8 min), abra a execução e baixe **dock-liquida-apk** em *Artifacts*. Dentro do zip está o `app-debug.apk`.
4. Envie o APK ao celular e instale. Se pedir, permita "instalar apps desconhecidos" para o navegador ou gerenciador de arquivos.

## Opção B: no computador
Requisitos: Node 18+, Java 17 e Android Studio.
```
npm install
npx cap add android
npx cap sync android
npx cap open android      # no Android Studio: Build > Build APK(s)
```
O APK sai em `android/app/build/outputs/apk/debug/`.

## Atualizar o app
Edite `www/index.html` e rode `npx cap sync android` e o build de novo (ou faça um novo push na Opção A).

## Observações
- O APK "debug" serve para uso próprio. Para publicar na Play Store é preciso um build *release* assinado.
- As configurações da dock ficam salvas no aparelho (localStorage do WebView).

## Tela inicial ao vivo (experimental)
Em vez de usar um print, o app fica transparente e mostra a tela inicial real por trás; só a dock é desenhada por cima.
No app: aba **Imagens > Tela inicial ao vivo**. Para funcionar, o tema Android precisa ser translúcido:
```
npm install
npx cap add android
node patch-android.js
npx cap sync android
npx cap open android
```
(O workflow do GitHub já roda o `patch-android.js` sozinho.)
Limites: o Android não deixa um app ler o layout nem fazer screenshot de outro app, então isto só mostra o que estiver por trás
no momento em que o app é aberto. Abra o app tocando no ícone dele na tela inicial. O resultado varia conforme o launcher.
