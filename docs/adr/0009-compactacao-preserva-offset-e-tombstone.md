# ADR 0009 — Compactacao preserva offset original e mantem o tombstone

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 4

## Contexto

Compactar e reescrever um segmento mantendo so o ultimo registro de cada chave.
Duas perguntas aparecem imediatamente, e as duas mudam garantias declaradas no
README.

1. Os registros que sobraram ficam com o offset original ou sao renumerados?
2. O tombstone e mantido ou descartado junto com o valor que ele apagou?

## Decisao

1. **O offset original e preservado.** O segmento compactado fica com lacunas.
2. **O tombstone e preservado.** Ele e o ultimo registro daquela chave.

## Por que preservar o offset

Renumerar seria mais bonito — o log continuaria sem lacunas. E seria errado.

O offset e a unica coordenada estavel que o braunlog oferece. Quem guardou "ja li
ate o offset 8.412" para retomar depois esta guardando um endereco; renumerar
transforma esse endereco em outro registro, em silencio. Um consumidor voltaria a
processar dados ja processados, ou pularia dados, sem nenhum erro em lugar nenhum.

O preco esta declarado: **a garantia "offset sem lacuna" vale ate a primeira
compactacao**. Depois dela, offsets continuam monotonicos e unicos, e podem ter
buracos. A lacuna e informacao, nao defeito: ela diz que algo ali foi substituido.

O formato ja suportava isso — `offsetRelativo` e gravado em cada registro, e nunca
foi deduzido da posicao.

## Por que preservar o tombstone

A tentacao e obvia: se a chave foi apagada, apagar tambem a marca de delecao e
liberar mais espaco.

O problema e o leitor que ainda nao chegou la. Se o tombstone sumir, um consumidor
que estava no offset 100 e que iria ver "chave a foi removida" no offset 500
simplesmente nunca ve a delecao — e continua achando que `a` tem o valor antigo,
que tambem foi compactado. O log passa a mentir para leitores lentos.

O Kafka resolve isso com `delete.retention.ms`: o tombstone e mantido por um tempo
grande o bastante para qualquer consumidor razoavel passar por ele. Fazer isso
aqui exigiria um segundo relogio de retencao e uma nocao de "consumidor mais
atrasado", que este projeto nao tem.

Entao o tombstone fica para sempre. Consequencia honesta: **uma chave deletada
deixa um rastro de 30 e poucos bytes que nunca some**. Para um log de chaves com
alta rotatividade, isso e um vazamento lento — e esta na lista de limitacoes do
README.

## Registro sem chave tambem sobrevive

Compactar significa "manter o ultimo por chave". Um registro sem chave nao tem
como ser substituido por outro, entao ele nunca e obsoleto e nunca e descartado.
Misturar eventos sem chave e pares chave/valor no mesmo log compactado funciona,
mas o log so encolhe na parte que tem chave.

## A ordem da troca atomica

A reescrita vai para `<base>.compactando.log`, e a troca acontece nesta ordem:

1. apaga o **indice antigo** do segmento;
2. `Files.move` do segmento novo por cima do antigo, com `ATOMIC_MOVE`;
3. `Files.move` do indice novo.

O passo 1 vem antes do 2 de proposito. Se a queda acontecer no meio, sobra um
segmento novo sem indice — que a abertura reconstroi varrendo. A ordem inversa
deixaria um indice velho apontando para posicoes que nao sao mais inicio de
registro, e a varredura a partir dali acusaria corrupcao num arquivo intacto.

Leitores em andamento sobrevivem porque em POSIX o `move` troca a entrada de
diretorio e o arquivo antigo continua vivo enquanto houver descritor aberto. Ao
fechar o segmento antigo, o leitor recebe `ClosedChannelException`, se reposiciona
pelo proximo offset que esperava e continua no arquivo novo. Prova:
`CompactacaoTest#leitorEmAndamentoDeveSobreviverATrocaDoSegmento`.

## O que fica em memoria

O mapa de chave para ultimo offset, para todas as chaves distintas dos segmentos
fechados. **Nenhum valor entra em memoria.** Ainda assim, compactar um log com
dezenas de milhoes de chaves distintas exige heap proporcional — a mesma limitacao
do compactador do Kafka, que tambem carrega um mapa de chaves.
