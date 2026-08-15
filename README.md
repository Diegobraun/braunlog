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

## Estado do projeto

| Fase | Conteudo | Situacao |
|---|---|---|
| 1 | formato do registro, append e leitura num arquivo, recuperacao basica | pronta |
| 2 | segmentos, rolagem, indice esparso, indice de tempo | proxima |
| 3 | modos de durabilidade, recuperacao completa, concorrencia | |
| 4 | retencao e compactacao por chave | |
| 5 | benchmarks JMH e acabamento | |

## Como rodar

```
mvn verify
```

O build so passa com cobertura de linha >= 85% (JaCoCo) e mutacao >= 85% (PIT).
Formatacao e verificada pelo Spotless na fase `validate`.
