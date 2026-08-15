# braunlog

Commit log append-only embarcado, escrito do zero em Java 25, sem nenhuma
dependencia de runtime. Segmentos em arquivo, indice esparso, recuperacao apos
queda, retencao e compactacao por chave — em algumas centenas de linhas que cabem
numa leitura so.

**Nao e** um broker, nao replica, nao tem rede, nao tem consenso, nao tem
transacao e nao compete com o Kafka em performance. E uma biblioteca que roda
dentro do seu processo.

## Por que existe

Todo mundo usa um log distribuido; quase ninguem ja abriu um arquivo de segmento
para ver o que tem la dentro. Este projeto existe para tornar isso concreto: o
formato binario esta especificado byte a byte em [`docs/formato.md`](docs/formato.md),
cada decisao tem uma ADR em [`docs/adr/`](docs/adr/), e cada garantia declarada
aponta para o teste que a prova.

Se voce quer entender por que um commit log e rapido, por que a recuperacao apos
queda funciona, e por que compactacao por chave nao e a mesma coisa que deletar
registro, o codigo aqui e pequeno o bastante para ser lido inteiro.

## Uso em 30 segundos

```java
var configuracao = ConfiguracaoLog.padrao(Clock.systemUTC());

try (var log = Log.abrir(Path.of("dados/pedidos"), configuracao)) {
  Offset offset = log.anexar(Registro.de("pedido-1".getBytes(UTF_8), "pago".getBytes(UTF_8)));
  log.forcarSincronizacao();

  try (Leitor leitor = log.lerDe(offset)) {
    while (leitor.hasNext()) {
      RegistroLido registro = leitor.next();
      System.out.println(registro.offset() + " -> " + new String(registro.valor(), UTF_8));
    }
  }
}
```

`Registro.tombstone(chave)` marca uma chave como removida e
`Registro.semChave(valor)` grava um evento sem chave. O `Leitor` e preguicoso: ele
le um registro por vez do disco e nunca carrega o log inteiro em memoria.

## Anatomia do log

Um log e um diretorio de **segmentos**. Cada segmento e um arquivo com nome igual
ao offset do primeiro registro que ele contem, preenchido com zeros a esquerda ate
20 digitos, e o conteudo e simplesmente um registro atras do outro — sem cabecalho
de arquivo, sem rodape.

Diagrama e invariantes: [`docs/diagramas/01-anatomia-do-log.md`](docs/diagramas/01-anatomia-do-log.md).

Tres consequencias que valem a pena entender antes de qualquer outra coisa:

- **O nome do arquivo carrega informacao.** Achar o segmento que contem o offset
  4.312 e uma busca binaria sobre a listagem do diretorio, sem abrir arquivo
  nenhum. Por isso o registro guarda `offsetRelativo` de 4 bytes em vez do offset
  absoluto de 8.
- **Nao existe atualizacao em lugar.** A unica escrita possivel e no fim do
  segmento ativo. Isso e o que permite dizer que um registro incompleto so pode
  existir no fim do arquivo, e e a base da recuperacao.
- **`tamanho` vem antes de `crc`.** Sem o tamanho na frente, seria impossivel
  distinguir "registro cortado por uma queda" de "registro corrompido no disco" —
  e essa distincao decide entre truncar o arquivo e gritar. Detalhes em
  [ADR 0003](docs/adr/0003-ordem-dos-campos-do-registro.md).

## Como um append funciona

Diagrama: [`docs/diagramas/02-append.md`](docs/diagramas/02-append.md).

O caminho e curto de proposito: valida o tamanho, decide se ainda cabe no segmento
ativo, codifica o registro num `ByteBuffer` com CRC32C, escreve na posicao do fim
do arquivo e **so entao** avanca o limite legivel.

Essa ultima frase e a parte que importa. `limiteLegivel` e um `volatile` que
nenhum leitor ultrapassa; ele so avanca depois de o registro inteiro estar
escrito. Nao existe lock entre o writer e os leitores — existe uma publicacao.
Um leitor ou ve o registro inteiro, ou nao ve registro nenhum.

A entrada do indice esparso e anotada **depois** da publicacao. Se o processo cair
entre as duas coisas, o indice perde uma entrada e o log continua correto: o
indice e uma dica, nao a verdade.

### Durabilidade, hoje

`anexar` retorna quando o `write` volta — ou seja, quando os bytes estao no page
cache do sistema operacional, nao no disco. Consequencia honesta:

| Situacao | O registro sobrevive? |
|---|---|
| Processo morre (`kill -9`) | Sim. O page cache e do sistema operacional, nao do processo. |
| Sistema operacional cai ou falta energia | Nao, a menos que `forcarSincronizacao()` tenha sido chamado. |

Os tres modos configuraveis — `ACadaAppend`, `PorIntervalo`, `Nenhum` — entram na
fase 3, junto com a tabela completa de garantias.

### Rolagem de segmento

Quando o proximo registro nao cabe em `bytesMaximosPorSegmento`, o segmento ativo
e fechado e um novo nasce com `offsetBase` igual ao proximo offset. Um segmento
vazio nunca rola, entao um registro maior que o limite do segmento ainda e aceito
— ele so ocupa um segmento sozinho.

## Como uma leitura funciona

Diagrama: [`docs/diagramas/03-leitura.md`](docs/diagramas/03-leitura.md).

```java
try (Leitor leitor = log.lerDe(Offset.de(4_312))) { ... }
try (Leitor leitor = log.lerDesde(Instant.parse("2026-08-15T12:00:00Z"))) { ... }
```

Tres passos: busca binaria sobre os offsets base dos segmentos para achar o
arquivo; busca binaria no indice esparso daquele segmento para achar um ponto de
partida; varredura para a frente ate o offset pedido. O `Leitor` e preguicoso e
atravessa segmentos sozinho — inclusive segmentos criados depois de ele ter
comecado a ler, o que permite acompanhar o writer sem reabrir o log.

### Por que o indice e esparso, e nao denso

A pergunta que ele responde nao e "onde esta o offset N", e sim **"de onde posso
comecar a varrer sem passar do offset N"**. Um indice denso responde a primeira em
tempo constante e cobra uma entrada por registro: 670 mil entradas num segmento de
64 MiB com registros de 100 bytes. O esparso responde a segunda com uma entrada a
cada 4 KiB — 16 mil entradas, 256 KiB — e a varredura restante cobre no maximo um
intervalo, que vem do page cache numa leitura so.

O detalhe que faz o desenho todo funcionar: **o indice nunca decide o que a
leitura devolve, so de onde ela comeca**. Apagar o indice, trunca-lo ou corrompe-lo
deixa a leitura mais lenta e igualmente correta — por isso ele nao tem CRC, nao
entra no fsync e e reconstruido na abertura seguinte.

Isso e testado de duas formas: apagando todos os indices e comparando a leitura
([`IndiceDoLogTest`](braunlog-core/src/test/java/com/braunlog/IndiceDoLogTest.java)),
e comparando, para dados aleatorios, a leitura com indice curto contra a leitura
com um indice de uma entrada so — que e varredura linear
([`LogPropriedadesTest`](braunlog-core/src/test/java/com/braunlog/LogPropriedadesTest.java)).

Um efeito colateral: como nenhum teste de leitura falharia se o indice parasse de
apontar direito, as dicas tem teste proprio em
[`SegmentoTest`](braunlog-core/src/test/java/com/braunlog/SegmentoTest.java).

### Leitura por tempo

O indice esparso guarda `timestamp` na mesma entrada que guarda `posicao`, entao
as duas buscas usam a mesma estrutura. Isso so e possivel porque o timestamp
gravado e **nao-decrescente**: o log grava `max(relogio, ultimoTimestamp)`. Se o
relogio da maquina voltar atras, o log nao mente sobre a ordem — perde resolucao
naquele intervalo. Detalhes em
[ADR 0005](docs/adr/0005-timestamp-nao-decrescente.md).

## Estado do projeto

| Fase | Conteudo | Situacao |
|---|---|---|
| 1 | formato do registro, append e leitura num arquivo, recuperacao basica | pronta |
| 2 | segmentos, rolagem, indice esparso, busca por tempo, benchmark inicial | pronta |
| 3 | modos de durabilidade, recuperacao completa, concurrency | proxima |
| 4 | retencao e compactacao por chave | |
| 5 | suite JMH completa e acabamento | |

## Como rodar

```
./mvnw verify
```

O build so passa com cobertura de linha >= 85% (JaCoCo) e mutacao >= 85% (PIT).
Formatacao e verificada pelo Spotless na fase `validate`.

Benchmarks:

```
./mvnw -q package -DskipTests
java -jar braunlog-benchmark/target/benchmarks.jar AppendBenchmark
```
