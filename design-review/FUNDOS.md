# Fundos e contraste no modo escuro

Em Free My TV → Ajustes do Início:

- **Plano de fundo:** Oceano, Dunas, Floresta, Pôr do sol, Neutro ou Automático.
- **Efeito do fundo:** Apenas o fundo ou Fundo desfocado.
- **Intensidade do desfoque:** de 0 a 100%, em passos de 5%.
- **Claridade do fundo:** de 0 a 100%, em passos de 5%.

Automático preserva os fundos anteriores, vindos das fotos e recomendações. Nesse modo, os controles específicos dos fundos predefinidos ficam indisponíveis, com uma explicação ao selecionar.

Os cinco fundos são estáticos, gerados localmente em Canvas, sem download nem animação contínua. Os efeitos são preparados fora da thread da interface e reutilizados ao desenhar. O tratamento dos cards e da prateleira usa uma cópia com desfoque fixo, independente da intensidade do fundo principal.

O texto focado agora acompanha o tema: claro sobre superfícies escuras, inclusive nas abas de Explorar, atalhos e valores das configurações.

## Verificação

- Compilação Android e atualização instalada na TV Box, API 24.
- Troca entre Oceano e Dunas, alternância do desfoque e ajustes de intensidade/claridade testados pelo controle.
- Temas claro e escuro verificados visualmente, incluindo foco nas abas e nas configurações.
- Preferências persistiram após encerrar e abrir novamente o processo do app.
- Estado final: Oceano, desfoque ligado, intensidade 35%, claridade 50%, tema Automático.
- Free My TV continua sendo o launcher padrão. Launcher original confirmado parado, sem processo ativo.
- Nenhuma exceção fatal nos trechos de log inspecionados.
- Prévia web: cinco fundos com imagens diferentes; aumento do desfoque reduziu a métrica de bordas de 313.661 para 23.643; aumento da claridade elevou a média RGB de 172,6 para 217,9; modo escuro reduziu a média para 105,1.
- Prévia web: configurações de Floresta/90%/80% persistiram após recarregar. Valores restaurados aos padrões ao concluir.
- Prévia web: texto focado escuro validado como `rgb(245, 245, 247)` nas abas e nas configurações; valores das configurações em `rgb(218, 218, 224)` sobre `rgb(48, 48, 54)`.
- JavaScript, diagnósticos do editor e empacotamento webOS/Tizen verificados. O pacote Tizen de teste não foi assinado.
- Os textos em português foram incluídos. A validação global de traduções ainda tem pendências em outros idiomas, como já ocorria antes desta alteração.

## Capturas

- [Configurações e foco legível](background-settings.png)
- [Tema claro, fundo sem desfoque](background-light.png)
- [Tema escuro, aba focada](background-dark-tab.png)
- [Configuração final](background-final.png)
