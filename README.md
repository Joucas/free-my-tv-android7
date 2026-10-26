# Free My TV — adaptações para Android 7

Versão pessoal de estudos baseada no [Free My TV original](https://github.com/simbasjungles/free-my-tv), com adaptações para Android 7 e mudanças na tela inicial, clima, indicadores do sistema e personalização visual.

**Projeto de estudos e para uso próprio. Não foi totalmente testado nem aprovado para uso geral. O único aparelho utilizado nos testes desta adaptação foi uma TV box MXQ Pro 4K 5g, com Android 7 / API 24.** Quem quiser experimentar pode testar, considerando essas limitações e mantendo uma forma de retornar ao launcher original.

O nome comercial “4K 5g” identifica o aparelho usado; não comprova resolução de renderização, conectividade ou características de outras unidades com esse nome. Os registros locais descrevem a unidade como Allwinner, com interface de teste em 1280 × 720.

Ainda pretendo fazer outras atualizações, principalmente para ajustar pequenos detalhes de design, espaçamentos, contraste, foco e acabamento. O estado atual é experimental.

## Origem e escopo

O código, os recursos e a licença originais foram preservados. A documentação original está em [README.upstream.md](README.upstream.md), como referência histórica: suas afirmações de compatibilidade e desempenho não representam validação desta adaptação.

O foco desta versão é Android. O código herdado para LG/webOS e Samsung/Tizen continua presente e recebeu adaptações de interface web, mas **não foi testado em TVs físicas dessas plataformas nesta versão**. Não há afiliação aos fabricantes ou serviços de streaming.

## Mudanças e adaptações

### Compatibilidade experimental com Android 7

- API mínima reduzida de 26 para 24 no empacotamento e no D8. O alvo continua sendo API 33; a compilação utiliza plataforma Android 34 ou superior.
- Fontes variáveis e APIs do Android 8 protegidas por versão. Android 7 usa fontes do sistema, regular e negrito.
- Ícones adaptativos isolados em uma classe utilizada apenas a partir da API 26; ícones tradicionais continuam disponíveis no Android 7.
- Temporizador com início de serviço, notificações e janela de sobreposição compatíveis com Android 7: `startService`, notificação legada e `TYPE_SYSTEM_ALERT`, com os caminhos modernos a partir da API 26. Permissões e restrições do firmware ainda se aplicam.
- Definição de HOME verifica também a atividade realmente resolvida pelo Android, pois alguns firmwares não retornam `Success` mesmo quando aceitam a mudança.
- Caminhos gráficos legados mantidos: Android 7 usa alternativas em Canvas e imagens preparadas para efeitos, sem os shaders AGSL do Android 13.
- Classpath do compilador Java ajustado para caminhos nativos no Git Bash/Windows.

Diminuir a API mínima permite tentar instalar o aplicativo; isso não garante todas as funções em qualquer aparelho com Android 7.

### Painel, aplicativos e navegação

- Cartões de clima e informações do sistema, relógio e prateleira de aplicativos.
- Ícones nas cores originais e foco com borda neutra para navegação pelo controle remoto.
- Prateleira alinhada aos cartões, aplicativos à esquerda e **Todos os Apps** fixo à direita.
- Atalhos abaixo da prateleira, com espaço reservado durante a navegação vertical.
- Chrome e Play Store podem aparecer quando já instalados e acessíveis. O projeto não instala esses serviços.
- Ajustes no reconhecimento do YouTube nas recomendações; disponibilidade ainda depende de APIs, região e aplicativos instalados.
- Na versão Android, cotações foram removidas em favor das informações do sistema, com ajustes nas configurações e no protetor de tela. A versão web mantém cotações opcionais.

### Informações do sistema

[DeviceMetrics.java](app/src/io/freemytv/DeviceMetrics.java) coleta CPU, memória, armazenamento e quadros renderizados enquanto a tela inicial está visível. A frequência da tela é uma informação distinta do FPS renderizado.

| Leitura | Limite |
|---|---|
| CPU | Depende de contadores em `/proc/stat`; o firmware pode bloquear o acesso |
| RAM | Usa os dados fornecidos pelo Android |
| Armazenamento | Volume onde está o diretório de arquivos do aplicativo |
| FPS | Quadros observados na janela do aplicativo; indisponível quando a amostra é insuficiente, inclusive com a tela parada |

Essas leituras não são um benchmark, não indicam FPS de vídeos ou outros aplicativos e não prometem economia de energia ou desempenho em outros aparelhos. Não houve medição controlada de consumo total.

### Clima e certificados antigos

O clima inclui temperatura, máximas e mínimas, previsão por horário, umidade, vento e sensação térmica. Os dados vêm do Open-Meteo e dependem de conexão, localização e disponibilidade do serviço.

Foi incluída a raiz pública **ISRG Root X1** em [isrg_root_x1.pem](app/res/raw/isrg_root_x1.pem), para firmwares antigos sem essa raiz. A validação consulta primeiro a confiança do sistema e permite a raiz adicional quando a cadeia é recusada. O tratamento fica restrito aos hosts previstos do Open-Meteo, mantendo a verificação de hostname. Não desativa globalmente a validação HTTPS nem resolve todos os problemas de rede de Android antigo.

### Fundos e aparência

Em **Free My TV → Ajustes do Início**:

| Opção | Valores |
|---|---|
| Plano de fundo | Oceano, Dunas, Floresta, Pôr do sol, Neutro ou Automático |
| Efeito do fundo | Original ou desfocado |
| Intensidade do desfoque | 0 a 100%, em passos de 5% |
| Claridade do fundo | 0 a 100%, em passos de 5% |
| Tema | Claro, escuro ou automático |

Os cinco fundos predefinidos são estáticos, gerados localmente e sem downloads. No Android, o processamento ocorre fora da thread da interface e o resultado é reutilizado. Cartões e prateleira mantêm seu tratamento de superfície separado do desfoque do fundo.

O modo Automático preserva fundos de fotos/recomendações e desabilita os controles específicos dos fundos predefinidos. As opções persistem no aparelho. Também foram ajustados os textos focados no tema escuro, incluindo abas, atalhos e configurações. A versão web acompanha os fundos, controles e ajustes visuais aplicáveis.

## Testes realizados e limites

As verificações abaixo resumem **registros locais de 24/10/2026**, em [VALIDACAO.md](design-review/VALIDACAO.md) e [FUNDOS.md](design-review/FUNDOS.md). Não são uma suíte completa nem certificação. A preparação desta documentação não repetiu os testes físicos.

| Verificação registrada | Alcance |
|---|---|
| Compilação e instalação do APK | MXQ Pro 4K 5g / API 24 |
| Navegação horizontal e vertical; Todos os Apps | Teste manual com controle remoto |
| Cores, espaçamentos, foco e temas | Inspeção de capturas em 1280 × 720 |
| Fundos, desfoque e claridade | Troca manual de opções |
| Persistência de preferências | Conferida após reabrir o processo do app |
| HOME e botão Home | Registros posteriores relatam Free My TV como padrão e persistência após reiniciar |
| Logs | Nenhuma exceção fatal nos trechos inspecionados; sem monitoramento contínuo |
| webOS/Tizen | Empacotamento, sintaxe JS e prévia no navegador; Tizen de teste sem assinatura |
| Traduções | Textos em português incluídos; check global ainda apresenta pendências |

Os registros representam etapas distintas: inicialmente o launcher original permaneceu como padrão; depois Free My TV foi definido como HOME. A troca completa de modo, que altera outros componentes, não foi integralmente validada nesse aparelho. A porta ADB TCP não ficou configurada após a reinicialização registrada; não se deve presumir recuperação automática por ADB local.

Pendências e próximos passos:

- Ampliar testes além desta única unidade. Mesmo aparelhos com o nome MXQ Pro 4K 5g podem ter placas e firmwares diferentes.
- Validar completamente temporizador, sobreposição, protetor de tela, acessibilidade e troca/restauração de modo.
- Realizar testes prolongados de estabilidade e desempenho; não há validação de consumo, temperatura ou reprodução/DRM em cada serviço.
- Testar em hardware LG/webOS, Samsung/Tizen e outras versões Android.
- Revisar traduções e chaves antigas: o check global ainda pode falhar e não deve ser apresentado como aprovado.
- Ajustar pequenos detalhes de design, foco, contraste, margens e transições em futuras atualizações.

## Compilar e configurar

Requisitos: JDK 17+, Android SDK Command-line Tools, build-tools 34, plataforma android-34 e ADB para instalar. O projeto usa Java e scripts, sem Gradle. No Windows, use Git Bash ou WSL com as ferramentas compatíveis.

```bash
sdkmanager "build-tools;34.0.0" "platforms;android-34"
bash build.sh
```

Configure `ANDROID_HOME` e `JAVA_HOME` se necessário. A primeira compilação cria o arquivo local `config.properties` a partir de [config.example.properties](config.example.properties). Edite-o e compile novamente.

| Configuração | Uso |
|---|---|
| `language=pt` | Português |
| `latitude`, `longitude`, `city` | Localização para clima e horário solar |
| `temperature=C` | Celsius |
| `tmdb_region=BR` | Região das recomendações |
| `tmdb_key` | Chave/token pessoal opcional do TMDB |
| `watchmode_key` | Chave opcional para abrir títulos nos serviços compatíveis |
| `show_crypto`, `currency` | Cotações da versão web; painel Android substituído |

Sem TMDB, a tela inicial, o clima e os fundos predefinidos continuam disponíveis, mas as recomendações dependentes da chave não. Não publique configurações pessoais, chaves de API ou arquivos de assinatura. Preserve a mesma keystore para assinar suas atualizações.

O resultado é `FreeMyTV.apk`. Para instalar no aparelho escolhido, com depuração autorizada:

```bash
adb devices
adb -s SEU_SERIAL install -r FreeMyTV.apk
adb -s SEU_SERIAL shell am start -n io.freemytv/.HomeActivity
```

Substitua `SEU_SERIAL` pelo identificador de `adb devices`. Abrir a atividade diretamente permite experimentar a interface antes de escolher usá-la como launcher padrão. Instalar e alterar HOME são etapas distintas.

## Retorno ao launcher original

A opção **Home Screen → Free My TV** do projeto original pode alterar o launcher, protetor de tela e desabilitar pacotes selecionados. **Original** e o watchdog dependem das condições da documentação original, inclusive ADB local disponível. Todos esses caminhos não foram verificados neste firmware.

Antes de trocar o padrão, registre o componente HOME e mantenha ADB USB autorizado. Consulta utilizada neste firmware:

```bash
adb -s SEU_SERIAL shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Para restaurar **somente HOME**, substitua o componente pelo que foi registrado no seu próprio aparelho:

```bash
adb -s SEU_SERIAL shell cmd package set-home-activity PACOTE/ATIVIDADE_ORIGINAL
adb -s SEU_SERIAL shell am start -n PACOTE/ATIVIDADE_ORIGINAL
```

Isso não restaura automaticamente pacotes ou configurações da troca completa de modo. Não desinstale o launcher original. O componente registrado nesta unidade foi `com.uv.droid.launcher.mxqlauncher/com.uv.droid.launcher.LauncherActivity`; ele pode ser diferente em outra TV box.

## Desenvolvimento

```bash
bash tools/check.sh android
bash tools/check.sh web
# Ou a sequência completa:
bash tools/check.sh
```

O check web exige Python 3, Node.js e acesso às dependências usadas pelo script. Inclui traduções, pacotes webOS/Tizen e sintaxe ES5. As pendências de tradução precisam ser resolvidas para a sequência completa passar. Compilação e prévia não comprovam funcionamento em hardware.

A [organização das implementações](project-notes/IMPLEMENTACOES.md) descreve os PRs e commits. Os comandos para criar o repositório e os títulos e textos completos dos PRs acompanham o pacote de publicação preparado para este estado dos arquivos.

## Privacidade, créditos e licença

Métricas são coletadas localmente. Clima usa Open-Meteo; recomendações usam TMDB e, quando configurado, Watchmode. A versão web pode consultar Binance. Esses serviços recebem as informações necessárias às requisições, como coordenadas configuradas e consultas de títulos.

- Origem: [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv).
- Clima: [Open-Meteo](https://open-meteo.com/).
- Recomendações: [TMDB](https://www.themoviedb.org/), sem endosso ou certificação; integração opcional com [Watchmode](https://api.watchmode.com/).
- Certificado público: [ISRG / Let's Encrypt](https://letsencrypt.org/certificates/).
- Fontes, ícones e demais créditos herdados: [documentação original](README.upstream.md#credits).

Licença [MIT](LICENSE). Preserve os avisos de copyright e os créditos dos recursos reutilizados.
