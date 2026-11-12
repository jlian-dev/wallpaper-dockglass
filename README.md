# Dock Líquida (nativo)

Versão 100% nativa (Kotlin + Jetpack Compose, **sem WebView**) do Wallpaper DockGlass.

- `glass/` — motor do Liquid Glass (Kotlin puro; refração por lei de Snell, bisel, aberração cromática, brilho, sombra). Port fiel do `script.js`.
- `render/` — decodificação (EXIF), corte/zoom/desfoque e pipeline da prévia.
- `data/` — biblioteca (wallpapers, álbuns, ajustes) e preferências.
- `system/` — aplicar wallpaper, salvar na Galeria, sobreposição "Alinhar" e troca automática (WorkManager).
- `ui/` — telas em Compose.

Build: `gradle assembleDebug` (Gradle 8.9, JDK 17) ou o workflow **Gerar APK** do GitHub Actions.
Mesmo `applicationId` do app anterior (`com.dockliquida.app`): se assinar com a mesma chave, o APK atualiza o antigo.

## Novidades da 2.1.0
- **Trocar foto** no editor (botão de vidro no topo): troca a imagem mantendo dock, álbum e tamanho.
- **Backup `.dckl`** (Ajustes › Backup): arquivo compactado (ZIP com extensão própria) com imagens, álbuns, ajustes das docks e preferências; também restaura.
- Barra inferior mais fina, painel do álbum com um único menu ⋮ e prévia maior (Início e editor).
