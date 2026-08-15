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

### Os tres modos de durabilidade

```java
ConfiguracaoLog.padrao(Clock.systemUTC())
    .comModoDurabilidade(ModoDurabilidade.A_CADA_APPEND);
```

| Modo | O que significa | O que pode ser perdido |
|---|---|---|
| `ACadaAppend` | `fsync` antes de `anexar` retornar | Nada depois do retorno. Custo: uma chamada de `fsync` por registro. |
| `PorIntervalo(d)` | `fsync` no primeiro append depois de vencido `d` | Ate `d` de escritas, se o sistema operacional cair. **Com o log ocioso, nada e sincronizado**: nao existe thread de background. |
| `Nenhum` (padrao) | Nunca chama `fsync` sozinho | Tudo que ainda estiver no page cache, se o sistema operacional cair. |

Duas quedas diferentes, que costumam ser confundidas:

- **Queda do processo** (`kill -9`): o page cache pertence ao sistema operacional
  e continua vivo. **Nenhum dado e perdido, em nenhum dos tres modos.** Isso e
  testado matando de verdade uma JVM filha com `SIGKILL`:
  [`QuedaDeProcessoTest`](braunlog-core/src/test/java/com/braunlog/QuedaDeProcessoTest.java).
- **Queda do sistema operacional** ou falta de energia: vale a tabela acima. Nao
  ha como provar isso de dentro de um teste, entao esta linha esta **declarada, e
  nao afirmada** — o que da para provar e que cada modo chama `fsync` quando diz
  que chama, e `Log#quantidadeDeSincronizacoes()` existe para que voce possa
  conferir isso de fora, em producao.

`close()` sincroniza antes de fechar, exceto no modo `Nenhum` — quem escolheu esse
modo pediu que o braunlog nunca chamasse `fsync`, e fechar nao e motivo para
desobedecer. Detalhes em [ADR 0006](docs/adr/0006-modos-de-durabilidade-sem-thread.md).

Na rolagem de segmento ha um `fsync` a mais, no **diretorio**: sincronizar o
arquivo nao grava a entrada dele no diretorio, e sem isso um segmento novo pode
sumir numa queda de energia mesmo com o conteudo ja duravel
([ADR 0007](docs/adr/0007-fsync-do-diretorio-na-rolagem.md)).

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

## Recuperacao apos queda

Diagrama: [`docs/diagramas/04-recuperacao.md`](docs/diagramas/04-recuperacao.md).

Abrir um log e responder uma pergunta: **onde continuar escrevendo?** O caminho
ate essa resposta toma tres decisoes que valem ser entendidas.

**Parcial nao e corrupcao.** Um registro cortado no meio, no fim do ultimo
segmento, e o rastro esperado de uma queda no meio de um append — o arquivo e
truncado ali e a vida segue. A mesma cauda parcial num segmento **fechado** e
corrupcao, porque nenhum caminho de codigo produz isso. E o campo `tamanho` vir
antes do `crc` que torna essa distincao possivel sem ler o arquivo inteiro.

**Truncar e obrigatorio.** Se os bytes parciais ficassem no disco, o proximo
append escreveria por cima do comeco deles e, se o registro novo fosse menor,
sobraria uma cauda de lixo que a leitura seguinte leria como registro. Escrita
parcial viraria corrupcao. Por isso o teste varre **todas** as posicoes de corte
possiveis, e nao uma amostra.

**A varredura comeca na ultima entrada do indice.** Abrir um log de 10 GB custa o
mesmo que abrir um de 10 MB. O preco esta declarado: corrupcao anterior a esse
ponto e detectada quando o registro for lido, nao na abertura.

## Retencao e compactacao

Sao duas formas de o log parar de crescer, e elas respondem perguntas diferentes:
retencao joga fora **o que e velho**; compactacao joga fora **o que foi
substituido**.

### Retencao apaga segmento inteiro — e essa e a unica opcao sa

```java
ConfiguracaoLog.padrao(relogio)
    .comPoliticaRetencao(PoliticaRetencao.combinada(
        PoliticaRetencao.porTamanhoTotal(10L << 30),
        PoliticaRetencao.porIdade(Duration.ofDays(7))));
```

"Apagar os registros com mais de sete dias" parece razoavel e nao existe num log
append-only. Apagar 200 bytes do meio de um arquivo obriga a mover todo o resto
para tras — o custo de I/O de uma copia completa para liberar 200 bytes — e ainda:

- todas as entradas do indice depois do buraco passam a apontar para o meio de um
  registro;
- escrever no meio do arquivo quebra a premissa que sustenta toda a recuperacao,
  que e "registro incompleto so pode existir no fim";
- um leitor concorrente veria o chao se mexer embaixo dele.

Apagar um arquivo inteiro nao tem nenhum desses problemas. O preco e a
granularidade: **o log so encolhe de um segmento por vez**, e o segmento ativo
nunca e apagado. Detalhes em [ADR 0008](docs/adr/0008-retencao-apaga-segmento-inteiro.md).

A retencao roda na abertura e a cada rolagem, sem thread de background — entao o
teto de tamanho e aproximado. `log.aplicarRetencao()` forca na hora.

### Compactacao mantem o ultimo valor de cada chave

Diagrama: [`docs/diagramas/05-compactacao.md`](docs/diagramas/05-compactacao.md).

```
antes    offset: 0    1    2    3    4    5    6
         chave:  a    b    a    c    a    b    d
                 v1   v1   v2   v1   v3   tomb v1

depois   offset: 3    4    5    6
         chave:  c    a    b    d
                 v1   v3   tomb v1
```

Duas decisoes moldam esse resultado, e as duas mudam garantias:

**Os offsets nao sao renumerados.** O offset e a unica coordenada estavel que o
braunlog oferece; quem guardou "ja li ate o 8.412" guardou um endereco. Renumerar
transformaria esse endereco em outro registro, em silencio, sem erro em lugar
nenhum. Entao o segmento compactado fica com **lacunas** — e a garantia "offset
sem lacuna" vale ate a primeira compactacao. Depois dela os offsets continuam
monotonicos e unicos, com buracos que dizem "algo aqui foi substituido".

**O tombstone e preservado.** Apagar a marca de delecao liberaria mais espaco e
faria o log mentir para quem le devagar: um consumidor no offset 100 nunca veria
a delecao que estava no 500, e continuaria achando que a chave tem o valor antigo.
O Kafka resolve isso com `delete.retention.ms`; aqui o tombstone fica para sempre,
e o custo — 30 e poucos bytes por chave deletada, para sempre — esta na lista de
limitacoes. Ver [ADR 0009](docs/adr/0009-compactacao-preserva-offset-e-tombstone.md).

Registros **sem chave** sobrevivem sempre: nao ha como serem substituidos.

### Dirty ratio: quando compensa reescrever

Compactar um segmento custa ler e escrever o segmento inteiro. Se so 5% dos bytes
estao obsoletos, esse custo compra 5% de espaco. `dirtyRatioMinimo` (0,5 por
padrao) e o botao que decide: um segmento so e reescrito quando pelo menos metade
dos bytes dele ja foi substituida. Dados que quase nao mudam ficam intocados.

A troca e atomica e leitores em andamento sobrevivem a ela — o `move` troca a
entrada de diretorio, o arquivo antigo continua vivo enquanto houver descritor
aberto, e o leitor se reposiciona pelo proximo offset que esperava.

## Garantias e nao-garantias

Nenhuma linha desta tabela esta aqui sem um teste que a prove.

| Garantia | Condicao | Prova |
|---|---|---|
| Registro lido e identico ao escrito | sempre | [`FormatoRegistroPropriedadesTest#oRegistroDecodificadoDeveSerIdenticoAoCodificado`](braunlog-core/src/test/java/com/braunlog/formato/FormatoRegistroPropriedadesTest.java), [`LogPropriedadesTest#tudoQueFoiAnexadoDeveVoltarIgualNaMesmaOrdem`](braunlog-core/src/test/java/com/braunlog/LogPropriedadesTest.java) |
| Corrupcao e detectada, nunca devolvida | sempre | [`RecuperacaoTest#deveDetectarCorrupcaoDeQualquerByteSemNuncaDevolverRegistroDiferenteDoEscrito`](braunlog-core/src/test/java/com/braunlog/RecuperacaoTest.java) — inverte cada byte de um segmento, um de cada vez |
| Corrupcao anterior ao ponto de varredura e detectada **na leitura**, nao na abertura | sempre | [`RecuperacaoTest#deveDetectarNaLeituraACorrupcaoQueAAberturaNaoVarreu`](braunlog-core/src/test/java/com/braunlog/RecuperacaoTest.java) |
| Escrita parcial nao e lida como valida | sempre | [`RecuperacaoTest#deveRecuperarTruncandoRegistroParcialEmQualquerPosicaoDeCorte`](braunlog-core/src/test/java/com/braunlog/RecuperacaoTest.java) e [`RecuperacaoComSegmentosTest#deveTruncarCaudaParcialDoUltimoSegmentoEmQualquerPosicaoDeCorte`](braunlog-core/src/test/java/com/braunlog/RecuperacaoComSegmentosTest.java) — **toda** posicao de corte |
| Depois da recuperacao da para continuar escrevendo | sempre | os dois testes acima anexam e conferem o offset resultante |
| Offset e monotonico, unico e crescente | sempre | [`SegmentacaoTest#deveManterOffsetContinuoESemLacunaEntreSegmentos`](braunlog-core/src/test/java/com/braunlog/SegmentacaoTest.java), verificado tambem em todo teste de recuperacao |
| Offset **sem lacuna** | ate a primeira compactacao | depois de compactar os offsets ficam com buracos, de proposito: [`CompactacaoTest#devePreservarOsOffsetsOriginaisDeixandoLacunas`](braunlog-core/src/test/java/com/braunlog/CompactacaoTest.java) |
| Compactacao preserva o ultimo valor de cada chave | sempre | [`CompactacaoPropriedadesTest#compactacaoDevePreservarOUltimoValorDeCadaChave`](braunlog-core/src/test/java/com/braunlog/CompactacaoPropriedadesTest.java) — comparado contra um `HashMap` com put e delete aleatorios |
| Compactacao e idempotente e sobrevive a reabertura | sempre | [`CompactacaoPropriedadesTest#compactarDuasVezesSeguidasNaoDeveMudarNada`](braunlog-core/src/test/java/com/braunlog/CompactacaoPropriedadesTest.java) e [`#compactacaoDeveSobreviverAReabertura`](braunlog-core/src/test/java/com/braunlog/CompactacaoPropriedadesTest.java) |
| Leitor em andamento sobrevive a troca do segmento compactado | sempre | [`CompactacaoTest#leitorEmAndamentoDeveSobreviverATrocaDoSegmento`](braunlog-core/src/test/java/com/braunlog/CompactacaoTest.java) |
| Retencao apaga segmento inteiro e nunca o ativo | sempre | [`RetencaoTest#nuncaDeveApagarOSegmentoAtivoMesmoQueAPoliticaMande`](braunlog-core/src/test/java/com/braunlog/RetencaoTest.java) |
| Registro confirmado sobrevive a queda do **processo** | nos tres modos | [`QuedaDeProcessoTest#registroConfirmadoDeveSobreviverAoKillDoProcessoEmQualquerModo`](braunlog-core/src/test/java/com/braunlog/QuedaDeProcessoTest.java) — `SIGKILL` numa JVM filha de verdade |
| Registro confirmado sobrevive a queda do **sistema operacional** | apenas modo `ACadaAppend` | **declarado, nao provado** — nao ha como derrubar o sistema operacional de dentro de um teste. O que e provado e que o modo chama `fsync` uma vez por registro: [`DurabilidadeTest#modoACadaAppendDeveSincronizarUmaVezPorRegistro`](braunlog-core/src/test/java/com/braunlog/DurabilidadeTest.java) |
| Registro confirmado **pode ser perdido** numa queda do sistema operacional | modos `PorIntervalo` e `Nenhum` | [`DurabilidadeTest#modoNenhumNuncaDeveSincronizarSozinho`](braunlog-core/src/test/java/com/braunlog/DurabilidadeTest.java) e [`#modoPorIntervaloNaoDeveSincronizarSozinhoComOLogOcioso`](braunlog-core/src/test/java/com/braunlog/DurabilidadeTest.java) mostram que o `fsync` nao acontece |
| Leitura concorrente durante escrita nao ve registro incompleto nem lacuna | sempre | [`ConcurrencyTest`](braunlog-core/src/test/java/com/braunlog/ConcurrencyTest.java) — um writer, quatro leitores, inclusive atravessando rolagem de segmento |
| O indice nao muda o que a leitura devolve | sempre | [`IndiceDoLogTest#oIndiceNaoDeveMudarOQueALeituraDevolve`](braunlog-core/src/test/java/com/braunlog/IndiceDoLogTest.java), [`LogPropriedadesTest#oIndiceEsparsoDeveConcordarComAVarreduraLinear`](braunlog-core/src/test/java/com/braunlog/LogPropriedadesTest.java) |
| Timestamp gravado e nao-decrescente | sempre | [`BuscaPorTempoTest#deveGravarTimestampNaoDecrescenteQuandoORelogioVoltaAtras`](braunlog-core/src/test/java/com/braunlog/BuscaPorTempoTest.java) |

### O que o braunlog **nao** garante

- Nao ha replicacao, consenso nem alta disponibilidade. Um disco, um processo.
- Nao ha protocolo de rede, broker nem consumer group.
- Nao ha transacao, exactly-once nem ordenacao entre logs diferentes.
- `PorIntervalo` nao da teto de tempo com o log ocioso ([ADR 0006](docs/adr/0006-modos-de-durabilidade-sem-thread.md)).
- Se o relogio da maquina voltar atras, o timestamp gravado perde resolucao
  naquele intervalo ([ADR 0005](docs/adr/0005-timestamp-nao-decrescente.md)).
- Um unico writer. `anexar` e `synchronized`, entao chamar de varias threads e
  seguro, mas nao e mais rapido.
- Depois de compactar, offsets tem lacunas. Quem depender de "offset + 1 e o
  proximo registro" quebra ([ADR 0009](docs/adr/0009-compactacao-preserva-offset-e-tombstone.md)).
- Um tombstone nunca e removido, entao chave deletada deixa ~30 bytes para sempre.
- A retencao pode apagar o segmento que um leitor esta lendo; ele salta para o
  proximo segmento vivo, sem erro e sem os registros que sumiram.
- Compactar carrega em memoria um mapa de todas as chaves distintas dos segmentos
  fechados. Nenhum valor entra em memoria, mas o numero de chaves importa.

## Estado do projeto

| Fase | Conteudo | Situacao |
|---|---|---|
| 1 | formato do registro, append e leitura num arquivo, recuperacao basica | pronta |
| 2 | segmentos, rolagem, indice esparso, busca por tempo, benchmark inicial | pronta |
| 3 | modos de durabilidade, recuperacao completa, concurrency | pronta |
| 4 | retencao e compactacao por chave | pronta |
| 5 | suite JMH completa e acabamento | pronta |

## Benchmarks

> **Aviso:** estes numeros existem para mostrar a forma das curvas — o efeito do
> fsync, do lote, do tamanho do registro e do intervalo do indice. Eles **nao sao
> comparacao com o Kafka** e nao fazem sentido fora do hardware declarado. Um
> commit log embarcado e um broker distribuido resolvem problemas diferentes;
> comparar os dois numeros seria desonesto.

A suite fica em `braunlog-benchmark`, com JMH:

```
./mvnw -q package -DskipTests
java -jar braunlog-benchmark/target/benchmarks.jar
```

| Benchmark | O que mede |
|---|---|
| `AppendBenchmark` | throughput de append por tamanho do valor (64, 512, 4096 bytes) |
| `DurabilidadeBenchmark` | tempo medio de um append em cada modo de durabilidade |
| `LoteBenchmark` | tempo de um lote de N registros com um unico `fsync` no fim |
| `LeituraBenchmark` | leitura sequencial, leitura por offset aleatorio, e efeito do intervalo do indice esparso |

### Como ler os resultados

Tres coisas aparecem sempre, e vale saber o que esperar antes de rodar:

- **O `fsync` domina tudo.** A diferenca entre `Nenhum` e `ACadaAppend` nao e uma
  constante do braunlog, e do dispositivo: e o tempo que o SSD leva para
  confirmar uma escrita durável. Nenhuma otimizacao de codigo mexe nisso.
- **O lote dilui esse custo.** Um `fsync` a cada 100 registros custa 1/100 por
  registro. E o mesmo raciocinio do `linger.ms` do Kafka, sem nenhuma magia — e a
  razao de todo sistema de log oferecer alguma forma de lote.
- **O intervalo do indice troca memoria por varredura.** Intervalo maior significa
  indice menor e varredura mais longa depois da busca binaria. Na leitura
  sequencial ele nao muda nada, porque o indice so e consultado no
  posicionamento inicial.

### Numeros medidos

Ainda nao publicados nesta versao: a maquina de referencia estava ocupada com
outra medicao quando esta fase foi fechada, e numero de benchmark colhido com a
CPU disputada nao vale como numero. A tabela entra assim que a suite rodar com a
maquina em repouso, junto com o hardware declarado (modelo de CPU, memoria,
sistema de arquivos e versao do JDK).

## Limitacoes, e o que eu faria diferente em producao

**Descritor de arquivo por segmento.** Todo segmento fica com o arquivo aberto
enquanto o log estiver aberto. Um log com milhares de segmentos precisa de
`ulimit` folgado. Em producao eu fecharia segmentos frios sob demanda, com um
cache de descritores — o Kafka tem exatamente esse problema e exatamente essa
solucao.

**Retencao e compactacao sao sincronas.** As duas rodam na thread de quem chamou
`anexar` (na rolagem) ou `compactar`. Compactar um segmento grande trava o writer
por todo o tempo da reescrita. Em producao isso e uma thread separada com
limitacao de banda — e ai aparece toda a complexidade de coordenacao que este
projeto evitou de proposito.

**`PorIntervalo` nao tem teto real com o log ocioso.** Sem thread de background,
o `fsync` so acontece no proximo append ([ADR 0006](docs/adr/0006-modos-de-durabilidade-sem-thread.md)).

**Tombstone nunca some.** Chave deletada deixa ~30 bytes para sempre. A solucao de
verdade e o `delete.retention.ms` do Kafka, que exige uma nocao de "consumidor
mais atrasado" ([ADR 0009](docs/adr/0009-compactacao-preserva-offset-e-tombstone.md)).

**Compactar carrega todas as chaves em memoria.** Nenhum valor, mas o numero de
chaves distintas importa. Em producao, um mapa fora do heap ou compactacao por
faixas de offset.

**Uma syscall de leitura por registro.** O `Varredor` le o cabecalho e depois o
corpo. Um buffer de leitura maior, amortizado entre registros, e a otimizacao
obvia — e ela so entra depois de o benchmark mostrar que vale
([ADR 0001](docs/adr/0001-io-posicional-em-vez-de-mmap.md)).

**Sem checksum no indice.** De proposito: o indice e descartavel. Mas isso
significa que um indice corrompido de forma "coerente" — offsets e posicoes
crescentes, apontando para lugares errados — passaria pela validacao e faria a
varredura comecar no meio de um registro, acusando corrupcao num segmento
intacto. A validacao cobre truncamento e lixo, nao corrupcao maliciosa.

**Um writer so.** `anexar` e `synchronized`. Escrita paralela exigiria alocar
offsets e posicoes de forma atomica e publicar fora de ordem — e a ordem e metade
do valor de um commit log.

## Referencias

O que foi lido para construir isto:

- **Kafka — documentacao de design do log**:
  [Log](https://kafka.apache.org/documentation/#log) e
  [Log Compaction](https://kafka.apache.org/documentation/#design_compactionbasics).
  A fonte direta do formato de segmento, do indice esparso e da compactacao por
  chave.
- **Jay Kreps, "The Log: What every software engineer should know about real-time
  data's unifying abstraction"** (2013). O texto que explica por que o log e uma
  abstracao, e nao um detalhe de implementacao.
- **Patrick O'Neil et al., "The Log-Structured Merge-Tree (LSM-Tree)"** (1996). De
  onde vem a ideia de que escrita sequencial mais compactacao em segundo plano
  ganha de atualizacao em lugar.
- **Mendel Rosenblum e John Ousterhout, "The Design and Implementation of a
  Log-Structured File System"** (1992). O artigo original da escrita sequencial
  como estrutura, incluindo o problema do espaco livre que vira retencao aqui.
- **Martin Kleppmann, _Designing Data-Intensive Applications_**, capitulos 3 e 11.
  A ponte entre o log como estrutura de armazenamento e o log como fluxo.
- **Pillai et al., "All File Systems Are Not Created Equal: On the Complexity of
  Crafting Crash-Consistent Applications"** (OSDI 2014). A origem da regra de
  sincronizar o diretorio, e um bom susto sobre o que os sistemas de arquivos
  realmente garantem.
- **Dan Luu, "Files are hard"** (2015). Resumo curto e util do mesmo assunto.
- **Guy Castagnoli et al., "Optimization of Cyclic Redundancy-Check Codes with 24
  and 32 Parity Bits"** (1993). O polinomio do CRC32C.
- **JEP 441 (pattern matching for switch)** e o guia de records do JDK, para o uso
  de `sealed interface` como resultado exaustivo.

## Como rodar

```
./mvnw verify
```

O build so passa com cobertura de linha >= 85% (JaCoCo) e mutacao >= 85% (PIT).
Formatacao e verificada pelo Spotless na fase `validate`. A analise de mutacao e a
parte demorada; para um ciclo rapido durante o desenvolvimento:

```
./mvnw verify -Dpitest.skip=true
```

O CI roda o `verify` completo e guarda os relatorios de cobertura e mutacao como
artefato. Os benchmarks estao na secao [Benchmarks](#benchmarks).
