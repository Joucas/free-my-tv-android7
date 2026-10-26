# Validação do layout

Referência: imagem fornecida na conversa. Teste em 24/10/2026, na TV Box Allwinner conectada por USB, Android API 24, interface em 1280 × 720.

## Resultado visual

Cartões de clima e sistema com proporções próximas à referência, fundo claro, cantos arredondados, barra superior com relógio e dock com foco de borda cinza neutra e ícones nas cores originais. Clima com previsão de quatro horários, umidade, vento e sensação térmica. CPU, memória, armazenamento e frequência da tela usam leituras do aparelho; FPS fica em branco quando não há quadros suficientes para medir.

As informações e os ícones disponíveis no aparelho produzem diferenças em relação ao mockup. O layout não declara 4K/HDR, tipo de RAM ou temperatura do chip sem dados que confirmem essas características.

- [Antes](before.png)
- [Layout atualizado, com foco no Chrome](after.png)
- [Todos os Apps durante o teste](apps.png)
- [Apps com as cores originais](colors.png)
- [Prateleira separada dos botões ao descer](options.png)

## Verificações

- Compilação Android concluída: `Built FreeMyTV.apk`.
- Atualização instalada com sucesso e tela aberta diretamente pela atividade do app.
- Comparação visual das capturas na TV Box com a referência.
- Navegação horizontal no dock e abertura de Todos os Apps confirmadas.
- Botão Home retornou ao launcher original. A resolução de HOME continuou em `com.uv.droid.launcher.mxqlauncher/com.uv.droid.launcher.LauncherActivity`.
- Nenhuma mudança do launcher padrão foi executada.
- Primeira abertura sem exceção fatal no trecho de log inspecionado. A leitura final dos logs não foi concluída após a desconexão ADB.
- Pacotes webOS e Tizen gerados; JavaScript validado com `node --check` e prévia web aberta no navegador. Tizen gerado sem assinatura para validação de empacotamento.
- `git diff --check` aprovado.

## Limitações

O verificador global de traduções ainda aponta chaves ausentes nos outros idiomas e chaves antigas obsoletas. Os novos textos em português foram incluídos; a revisão completa dos demais idiomas permanece pendente.

## Segunda rodada: cores e espaço entre prateleira e botões

Conexão USB restabelecida. Removido o filtro de cinza no Android e na versão web. O deslocamento da prateleira agora usa sua posição real e reserva 64 unidades do layout acima dos atalhos (aproximadamente 43 pixels na interface de 1280 × 720). Os botões aparecem quando a prateleira libera sua área durante a animação.

Nova compilação Android e instalação concluídas. Capturas confirmam os ícones coloridos e o espaço livre acima dos botões. Navegação para baixo, horizontal e de volta para cima repetida três vezes. Nenhuma exceção fatal nos trechos de log inspecionados. Pacotes webOS/Tizen reconstruídos, JavaScript e diagnósticos do editor aprovados.

Na reconexão, o Android estava resolvendo HOME para Free My TV, apesar de o estado interno do aplicativo indicar modo original. Restaurado somente o destino de HOME para `com.uv.droid.launcher.mxqlauncher/com.uv.droid.launcher.LauncherActivity`. O botão Home abriu o launcher original no teste. Free My TV reaberto diretamente para conferir o layout, preservando o launcher original como padrão.

## Terceira rodada: minimalismo e prateleira ampla

Removidos o marcador antes do título e a pill SMART HUB. Foco padronizado com borda fina cinza, sem ampliar a sombra dos atalhos. Prateleira alinhada às margens dos cards, aplicativos alinhados à esquerda e Todos os Apps fixo no extremo direito. A rolagem horizontal preserva o botão fixo, inclusive na lista de itens acessíveis.

- [Tela inicial atual](minimal-home.png)
- [Atalhos e foco padronizado](minimal-options.png)
- [Todos os Apps aberto pelo botão da direita](minimal-apps.png)

Compilação Android, instalação, navegação vertical e abertura de Todos os Apps verificadas na TV Box. A prévia web também abriu Todos os Apps pelo novo botão. Pacotes webOS/Tizen gerados e JavaScript validado. Nenhuma exceção fatal nos trechos de log inspecionados. HOME segue apontando para o launcher original; o teste com o botão Home confirmou sua abertura. Free My TV aberto diretamente para uso e teste.

Nenhum serviço de streaming adicional foi instalado ou incorporado. Acesso por navegador depende do suporte do serviço ao navegador e ao aparelho; reprodução nesses serviços não foi testada.
