# Fase 2 — o indice que pode estar errado sem quebrar nada

A decisao dificil desta fase nao foi escrever a busca binaria. Foi entender que
tipo de estrutura o indice esparso e — e o que isso libera.

## Uma dica, nao uma fonte de verdade

O primeiro desenho tratava o indice como um mapa: "offset 4312 esta na posicao
918273". Com um mapa, tudo fica caro. O mapa precisa estar certo, entao precisa de
CRC. Precisa sobreviver a queda, entao precisa entrar no fsync. Precisa ser
atualizado junto com o segmento, entao entra no caminho critico do append. E
quando ele estiver corrompido, alguem precisa decidir o que fazer com o log
inteiro.

O segundo desenho trata o indice como uma dica: "de onde da para comecar a varrer
sem risco de passar do offset 4312". Aritmeticamente e quase a mesma coisa. Em
consequencias, e outro projeto:

- Nao precisa de CRC, porque uma entrada errada nao produz dado errado — produz um
  ponto de partida ruim, e a varredura corrige.
- Nao precisa de fsync, porque pode ser reconstruida varrendo o segmento.
- Entrada incoerente na abertura nao e erro: o arquivo e truncado ali e a vida
  segue.
- Pode ser apagado por completo, e o log continua correto.

A pergunta muda de "o indice esta certo?" para "o indice esta *seguro*?" — e a
resposta e sim, desde que ele nunca aponte para depois do alvo. E so isso que a
validacao de carga confere: offsets e posicoes crescentes, timestamps
nao-decrescentes, posicao dentro do segmento.

## O teste que essa escolha exige

Se o indice pode estar errado sem quebrar nada, entao **nenhum teste de leitura
pega um indice quebrado**. Descobri isso pelo relatorio de mutacao: o PIT trocou
`posicaoParaOffset` por "devolve sempre zero" e a suite inteira continuou verde.
Devia mesmo — com zero, toda leitura varre desde o inicio do segmento e devolve a
resposta certa, so mais devagar.

O mutante sobrevivente estava dizendo uma verdade sobre o desenho, nao apontando
um teste fraco. A resposta foi separar as duas coisas:

- a **correcao** da leitura, testada comparando a leitura com indice curto contra
  a leitura com um indice de uma entrada so, que e varredura linear;
- a **utilidade** do indice, testada direto: `posicaoParaOffset` de um offset la
  no meio do segmento tem que devolver posicao maior que zero.

Sem essa separacao, o indice poderia parar de funcionar em producao e nenhum
teste diria nada.

## O segundo indice que nao existe

O Kafka tem `.index` e `.timeindex`. Aqui virou um arquivo so, e o motivo e uma
invariante: as tres colunas da entrada — offset, posicao e timestamp — crescem
juntas. Offset e posicao porque o log e append-only. Timestamp porque o append
grava `max(relogio, ultimoTimestamp)`.

Essa ultima parte incomoda, e vale encarar de frente: o log **altera** o valor que
o relogio deu. Se o NTP puxar o relogio uma hora para tras, os registros da hora
seguinte recebem o timestamp do ultimo registro anterior.

A alternativa seria gravar o valor do relogio como veio e aceitar timestamps fora
de ordem. Ai a busca binaria por tempo passa a devolver uma resposta plausivel e
errada — e nao ha teste confiavel para isso, porque depende de quando o relogio
voltou. Entre perder resolucao de tempo num intervalo raro, de forma declarada, e
devolver resposta errada em silencio, a escolha nao e dificil. Mas ela precisa
estar escrita no README, e esta.

## O que a varredura de abertura deixou de fazer

Na fase 1, abrir o log varria o arquivo inteiro. Agora a varredura comeca na
ultima entrada do indice — no maximo um intervalo antes do fim. Abrir um log de
10 GB passou a custar o mesmo que abrir um de 10 MB.

O preco esta declarado: corrupcao num registro anterior a esse ponto nao e vista
na abertura. Ela e vista na leitura daquele registro, que e onde o CRC e
conferido. A garantia — "corrupcao e detectada, nunca devolvida" — continua
inteira; o que mudou foi o **momento**. Isso tem teste proprio, porque uma
mudanca de momento sem teste vira, com o tempo, uma mudanca de garantia.
