# Diário de Ferro

App de treino do Edu (uso próprio), em HTML/JS puro, sem dependências.

- Código-fonte: `src/app.html` (é o mesmo conteúdo publicado como artefato no claude.ai: https://claude.ai/artifact/KvkvKZoHj1nUhRAQ9zwZnM).
- Versão instalável (PWA, offline): gerada por `python3 tools/build.py`, que escreve `index.html`, `manifest.json` e `sw.js` na raiz.
- Publicação: GitHub Pages a partir de `main` / raiz → https://eduberigo.github.io/diario-de-ferro/
- Fluxo de mudança: editar `src/app.html` → `python3 tools/build.py` → commit e push → republicar o artefato com o mesmo conteúdo de `src/app.html`.
- Dados do usuário ficam no aparelho (localStorage); no artefato, também no banco do artefato. Nunca mudar a chave `ferro.v1` sem migração.
- Nomes dos exercícios seguem o documento "Trocas Emergenciais" do treinador (lista DOC_EX e TROCAS).
- App Android nativo: `android-app/` (Capacitor). `tools/build.py` também copia a página para `android-app/www`. O GitHub Actions (`.github/workflows/android.yml`) compila o APK a cada push em `main` e publica em Releases com a tag `android-latest`: https://github.com/eduberigo/diario-de-ferro/releases/download/android-latest/diario-de-ferro.apk
- A chave de assinatura (`android-app/ferro.jks` + `keystore.properties`) não pode mudar: com outra chave, a atualização não instala por cima e o app perderia os dados.
- Recursos nativos ficam em `src/app.html` atrás de `NAT` (notificação do treino com botões, alarme do fim do cronômetro, tela ligada, compartilhar arquivo). No navegador e no claude.ai esse código não roda.
