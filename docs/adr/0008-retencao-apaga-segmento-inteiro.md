# ADR 0008 — Retencao apaga segmento inteiro, nunca registro isolado

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 4

## Contexto

"Apagar os registros com mais de sete dias" parece uma operacao razoavel. Num log
append-only, ela nao existe.

## Decisao

A unidade de retencao e o **segmento**. Um segmento e apagado inteiro ou nao e
apagado. O segmento ativo nunca e apagado.

Politicas: `PorTamanhoTotal(bytes)`, `PorIdade(duracao)`, `Combinada(...)` — que
descarta quando qualquer uma das politicas mandar — e `Nenhuma`, que e o padrao.

## Por que nao da para apagar registro isolado

1. **Nao ha buraco num arquivo.** Apagar 200 bytes do meio de um arquivo obriga a
   mover todo o resto para tras. Isso e reescrever o arquivo inteiro, com o custo
   de I/O de uma copia completa, para liberar 200 bytes.
2. **Mover bytes invalida tudo que aponta para posicoes.** Todas as entradas do
   indice esparso depois do buraco passariam a apontar para o meio de um registro.
   O indice teria de ser reconstruido a cada delecao.
3. **Escrita no meio do arquivo quebra a recuperacao.** Toda a analise de queda do
   braunlog se apoia numa unica premissa: escrita so acontece no fim do arquivo,
   logo registro incompleto so pode existir no fim. Uma escrita no meio quebra
   essa premissa, e com ela a distincao entre "parcial" e "corrompido".
4. **Um leitor concorrente veria o chao se mexer.** Hoje um leitor posicionado na
   posicao 5000 sabe que aquela posicao continua sendo o inicio do mesmo registro.

Deletar um arquivo inteiro nao tem nenhum desses problemas: e uma operacao do
sistema de arquivos, o indice do segmento vai junto e nenhuma posicao muda.

## Consequencias

- **A granularidade da retencao e o tamanho do segmento.** Configurar
  `bytesMaximosPorSegmento` alto e retencao apertada nao funciona bem: o log so
  encolhe de um segmento por vez.
- **O limite de tamanho e um teto aproximado, nao exato.** A retencao roda na
  abertura e a cada rolagem; entre duas rolagens o segmento ativo cresce livre.
  Nao ha thread de background, pela mesma razao da [ADR 0006](0006-modos-de-durabilidade-sem-thread.md).
  Quem quiser um teto mais firme chama `aplicarRetencao()`.
- **Um leitor pode perder registros.** Se a retencao apagar o segmento que um
  leitor estava lendo, ele salta para o primeiro segmento vivo. Nao ha erro nem
  dado errado, ha um pulo — e retencao e destrutiva por definicao. Prova:
  `RetencaoTest#leitorPosicionadoNumSegmentoApagadoDeveSaltarParaOProximoSegmentoVivo`.
- **O primeiro offset do log deixa de ser zero.** Quem depender disso quebra; a
  API sempre expos `ultimoOffset()` e nunca prometeu que o primeiro fosse zero.
