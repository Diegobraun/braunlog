# ADR 0004 — Um unico indice esparso para offset e para tempo

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 2

## Contexto

Ler a partir de um offset arbitrario exige achar a posicao em bytes daquele
registro dentro do segmento. Duas familias de solucao:

- **Indice denso**: uma entrada por registro. Busca em tempo constante, custo de
  espaco proporcional ao numero de registros.
- **Indice esparso**: uma entrada a cada N bytes. Busca binaria ate o ponto
  anterior mais proximo, depois varredura para a frente.

E ha uma segunda pergunta: buscar por tempo precisa de um segundo indice?

## Decisao

**Indice esparso**, uma entrada a cada `intervaloDoIndiceEmBytes` (4 KiB por
padrao), num **unico arquivo** que serve as duas buscas. Entrada de 16 bytes:
`offsetRelativo`, `posicao`, `timestamp`.

## Motivos

### Por que esparso

1. **O indice precisa caber na memoria; o log nao.** Um indice denso de um
   segmento de 64 MiB com registros de 100 bytes teria 670 mil entradas. Esparso
   a cada 4 KiB, sao 16 mil entradas — 256 KiB. A diferenca decide se o indice de
   um log grande cabe no heap.
2. **A varredura complementar e barata.** Depois da busca binaria, sobra no
   maximo um intervalo para varrer: 4 KiB, que vem do page cache numa unica
   leitura de disco. Um indice denso trocaria essa varredura por um seek — que
   nao e mais barato.
3. **Escrita mais barata.** Uma entrada a cada 4 KiB, e nao uma por registro,
   tira o indice do caminho critico do append.

O intervalo e o botao que troca memoria por varredura. O benchmark da fase 5 mede
esse trade-off.

### Por que um arquivo so

O Kafka mantem `.index` e `.timeindex` separados. Aqui os dois viraram um, porque
**as tres chaves da entrada crescem juntas**: `offsetRelativo` e `posicao` porque
o log e append-only, e `timestamp` porque o append forca timestamp
nao-decrescente (ADR 0005). Uma sequencia ordenada pelas tres chaves ao mesmo
tempo responde as duas buscas binarias com o mesmo codigo.

O custo e 8 bytes a mais por entrada esparsa — 0,2% do intervalo de 4 KiB. O
ganho e uma classe, um formato e um caminho de recuperacao em vez de dois.

### Por que o indice nao tem CRC nem fsync

Porque ele e **descartavel**. Qualquer entrada pode ser reconstruida varrendo o
segmento, e o segmento e a unica fonte de verdade. Na abertura, uma entrada
incoerente faz o arquivo ser truncado ali, sem erro: perder entrada custa
varredura, nunca dado errado.

Prova: `IndiceDoLogTest#oIndiceNaoDeveMudarOQueALeituraDevolve` apaga todos os
indices e compara a leitura com a de antes;
`LogPropriedadesTest#oIndiceEsparsoDeveConcordarComAVarreduraLinear` compara,
para dados aleatorios, a leitura com indice curto contra a leitura com um
intervalo tao grande que o indice tem uma entrada so — ou seja, varredura linear.

## Consequencias

- Nenhum teste de leitura falharia se as dicas do indice parassem de funcionar —
  o resultado continuaria certo, so mais lento. Por isso as dicas tem teste
  proprio: `SegmentoTest`.
- A varredura de abertura comeca na **ultima entrada do indice**, nao no inicio
  do arquivo. Isso torna a abertura proporcional a um intervalo, e nao ao tamanho
  do segmento. O preco esta declarado no README: corrupcao anterior a esse ponto
  e detectada na leitura do registro, nao na abertura.
