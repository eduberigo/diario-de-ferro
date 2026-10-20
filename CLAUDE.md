# Diário de Ferro

App de treino do Edu (uso próprio), em HTML/JS puro, sem dependências.

- Código-fonte: `src/app.html` (é o mesmo conteúdo publicado como artefato no claude.ai: https://claude.ai/artifact/KvkvKZoHj1nUhRAQ9zwZnM).
- Versão instalável (PWA, offline): gerada por `python3 tools/build.py`, que escreve `index.html`, `manifest.json` e `sw.js` na raiz.
- Publicação: GitHub Pages a partir de `main` / raiz → https://eduberigo.github.io/diario-de-ferro/
- Fluxo de mudança: editar `src/app.html` → `python3 tools/build.py` → commit e push → republicar o artefato com o mesmo conteúdo de `src/app.html`.
- Dados do usuário ficam no aparelho (localStorage); no artefato, também no banco do artefato. Nunca mudar a chave `ferro.v1` sem migração.
- Nomes dos exercícios seguem o documento "Trocas Emergenciais" do treinador (lista DOC_EX e TROCAS).
