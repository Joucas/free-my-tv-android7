# Organização das implementações

Base original: commit `f2d984c`. Os patches da entrega preservam o histórico e separam os trechos de compatibilidade e interface compartilhados no mesmo arquivo.

| PR | Branch | Escopo | Commits |
|---|---|---|---|
| 1 | `feat/android7-compat` | Build API 24, fontes, ícones, temporizador e HOME | 2 |
| 2 | `feat/android-dashboard` | Painel Android, clima, métricas, navegação e fundos | 3 |
| 3 | `feat/web-dashboard` | Interface e personalização web | 2 |
| 4 | `docs/personal-study-release` | README, origem e registros de validação | 2 |

Crie cada branch a partir de `main` depois de integrar o PR anterior. Use merge commit para preservar os commits; squash os condensaria. Painel, clima e fundos Android ficam juntos porque a implementação atual altera conjuntamente renderização, preferências e configurações. Separar essas partes em PRs independentes exigiria construir e testar versões intermediárias do produto.

## PR 1

1. `build(android): permitir API 24 e corrigir classpath no Windows`
2. `fix(android): adaptar fontes icones servicos e HOME ao Android 7`

O trecho de compatibilidade de [HomeApps.java](../app/src/io/freemytv/HomeApps.java) é separado das alterações de prateleira do PR 2. Inclui atualização dos comentários de caminhos gráficos e seleção do screensaver.

## PR 2

1. `feat(metrics): coletar CPU memoria armazenamento e FPS da janela`
2. `feat(home): integrar painel clima dock e fundos configuraveis`
3. `i18n(pt): traduzir painel e controles de personalizacao`

O segundo commit integra métricas, HOME, preferências, configurações, protetor de tela, clima ampliado, raiz pública ISRG Root X1, remoção de cotações Android, Chrome/Play Store e reconhecimento do YouTube. Os controles de fundo entram com suas dependências.

## PR 3

1. `feat(web): preparar clima ampliado e fundos locais configuraveis`
2. `feat(web): atualizar painel navegacao e contraste dos temas`

Dados, preferências e gerador de fundos entram antes de sua conexão à interface. Não promete leituras de hardware Android na versão web. Cotações opcionais web permanecem.

## PR 4

1. `docs: documentar adaptacao pessoal testes limitados e proximos passos`
2. `docs: registrar validacoes visuais e excluir diagnosticos locais`

Preserva a documentação original, registra a MXQ Pro 4K 5g como único hardware testado e inclui os relatos e capturas selecionadas. São evidências históricas; a preparação desta entrega não repete os testes físicos.

Os dumps XML de contas/loja, logs locais e o script de recuperação com caminho e serial pessoais ficam fora dos patches. Revise as capturas selecionadas antes de tornar o repositório público. O certificado incluído é uma raiz pública, não uma chave privada.

## Verificação

Execute os checks do [README](../README.md#desenvolvimento), revise os diffs e registre o resultado real. Pendências globais de traduções continuam conhecidas e devem permanecer visíveis até serem corrigidas. Não marque testes históricos como executados novamente.

Os patches correspondem ao estado desta entrega. Se editar o código depois, atualize-os antes de publicar.
