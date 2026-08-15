# Fase 3 — a garantia que nao da para provar

Esta fase era sobre `fsync`. Acabou sendo sobre a diferenca entre o que da para
provar e o que da para afirmar.

## Duas quedas, nao uma

"O dado sobrevive a uma queda" e uma frase sem sentido enquanto voce nao disser
qual queda.

Matar o processo com `kill -9` nao perde nada, em nenhum dos tres modos. O
`write` entregou os bytes ao page cache, e o page cache pertence ao sistema
operacional — o processo pode morrer inteiro que os bytes continuam la, e o
proximo processo que abrir o arquivo vai encontra-los. Isso surpreende muita
gente, inclusive quem escreve codigo de persistencia ha anos.

Derrubar o sistema operacional e outra coisa. Ai o page cache vai junto, e so
sobrevive o que ja tinha ido para o disco — o que so acontece com `fsync`.

A tabela de durabilidade do README foi reescrita com essas duas colunas
separadas, porque juntar as duas quedas numa linha so e a origem de quase toda
confusao sobre durabilidade.

## O teste que mata um processo de verdade

A primeira metade da tabela da para provar, e vale a pena provar direito. O teste
sobe uma JVM filha, manda ela escrever 200 registros, espera ela imprimir
`pronto` na saida padrao — sem `sleep`, so lendo a linha, que bloqueia sozinha —,
mata com `SIGKILL` e reabre o log no processo pai.

Nada de mock, nada de simulacao. Se o dado nao estivesse no page cache, esse teste
falharia.

A segunda metade nao da para provar. Nao existe forma de derrubar o sistema
operacional de dentro de um teste. Havia duas saidas: escrever a linha assim
mesmo, como todo mundo escreve, ou marca-la como **declarada, nao provada** e
provar a coisa adjacente — que o modo chama `fsync` quando diz que chama.

Escolhi a segunda, e por isso `Log#quantidadeDeSincronizacoes()` e publico. Nao e
um gancho de teste: sem ele, os tres modos sao indistinguiveis de fora, e uma
garantia que ninguem consegue observar nao e garantia, e promessa.

## A thread que nao existe

`PorIntervalo(200ms)` deveria ter uma thread de background. Toda implementacao
tem. A minha nao tem, e isso muda a garantia.

O motivo nao foi preguica. Uma thread de background so pode ser testada com
relogio real e espera — exatamente o que este projeto proibiu logo no inicio.
Para testar com relogio injetado eu precisaria injetar tambem um agendador: uma
interface cuja unica implementacao real e `ScheduledExecutorService` e cuja unica
outra implementacao existe so para o teste. Isso e a abstracao-para-teste que a
lista de antipadroes do projeto proibe.

Entao o modo sincroniza no primeiro append depois de vencido o intervalo, e a
garantia ficou mais fraca e mais honesta:

> Enquanto houver escrita, no maximo `intervalo` fica sem `fsync`. Com o log
> ocioso, nada e sincronizado.

Tem um teste chamado
`modoPorIntervaloNaoDeveSincronizarSozinhoComOLogOcioso` que existe exatamente
para fixar essa limitacao. Se alguem adicionar a thread depois, ele quebra — e a
conversa sobre a garantia acontece antes do merge, nao depois do incidente.

## O fsync que quase ficou de fora

`fsync` num arquivo grava o conteudo do arquivo. Ele nao grava a **entrada do
arquivo no diretorio**. Sao dois blocos de metadados diferentes, e o sistema de
arquivos pode adiar o segundo.

Na pratica: um segmento novo, com todos os registros ja sincronizados, pode
simplesmente nao existir depois de uma queda de energia. O arquivo estava
duravel; o nome dele, nao.

A correcao e uma linha — abrir o diretorio como canal e chamar `force` — e ela
esta na rolagem de segmento. E o unico `catch` vazio do projeto, porque no Windows
nao da para abrir diretorio como canal, e derrubar um append por causa disso seria
trocar um risco raro por uma falha certa. Esse `catch` tem javadoc explicando o
porque, e e o tipo de detalhe que so aparece quando alguem senta para escrever a
garantia por extenso.
