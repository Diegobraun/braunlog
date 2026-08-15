# ADR 0005 — Timestamp gravado e nao-decrescente

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 2

## Contexto

Buscar por tempo com busca binaria exige que os timestamps do log estejam
ordenados. Mas `Clock` nao garante isso: um ajuste de NTP, uma correcao manual ou
uma virada de horario de verao podem fazer o relogio voltar atras entre dois
appends. Se isso acontecer, a busca binaria fica errada — e errada em silencio,
que e o pior tipo.

Opcoes:

1. Aceitar timestamp fora de ordem e trocar a busca binaria por varredura linear.
2. Guardar o maior timestamp de cada segmento e usar isso para rotear, aceitando
   resposta aproximada dentro do segmento.
3. Forcar o timestamp gravado a ser nao-decrescente.

## Decisao

**Opcao 3**: o log grava `max(relogio.millis(), timestampDoUltimoRegistro)`.

## Motivos

1. **A invariante fica no lugar mais barato de manter.** Uma comparacao por
   append, contra uma varredura linear em toda busca por tempo.
2. **A alternativa esconde o problema.** Com timestamps fora de ordem, a busca
   binaria devolveria um offset plausivel e errado. Nao ha teste que pegue isso
   de forma confiavel, porque depende de quando o relogio voltou.
3. **O indice unico depende disso.** Se o timestamp nao fosse ordenado, seria
   preciso um segundo indice ordenado por tempo (ADR 0004).
4. **E o que o Kafka faz com `LogAppendTime`.** Quando o broker carimba o tempo,
   ele tambem garante monotonicidade por particao.

## Consequencias

- Se o relogio voltar uma hora, os registros gravados nessa hora recebem o
  timestamp do ultimo registro anterior. O log **nao mente sobre a ordem**, mas
  perde resolucao nesse intervalo. Isso esta documentado no README, na tabela de
  garantias.
- A invariante sobrevive a reabertura: o log le o ultimo timestamp gravado ao
  abrir cada segmento. Prova:
  `BuscaPorTempoTest#deveManterOTimestampNaoDecrescenteDepoisDeReabrir`.
- O braunlog nao suporta o modo "tempo do produtor" (`CreateTime` no Kafka), em
  que quem escreve escolhe o timestamp. Suportar isso exigiria abrir mao da
  ordenacao ou validar cada valor recebido, e nenhum dos dois cabe no escopo.
