# ADR 0006 — Modos de durabilidade sem thread de background

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 3

## Contexto

O modo `PorIntervalo(200ms)` promete "no maximo 200ms de escritas sem fsync". A
implementacao obvia e uma thread de background que acorda a cada 200ms e chama
`force`. Foi o primeiro desenho.

Duas coisas travaram esse caminho:

1. **Teste.** Uma thread de background so pode ser testada com relogio real e
   espera — exatamente o que este projeto proibiu. Testar com relogio injetado
   exigiria injetar tambem um agendador, ou seja, criar uma abstracao cuja unica
   implementacao real e `ScheduledExecutorService` e cuja unica outra
   implementacao existe para o teste.
2. **Ciclo de vida.** Uma thread dentro de uma biblioteca embarcada precisa ser
   criada, nomeada, marcada como daemon, encerrada no `close`, e ainda assim
   sobra o caso do `close` que nunca acontece. E estado de fundo num projeto que
   se propos a nao ter estado de fundo.

## Decisao

`PorIntervalo` sincroniza **no primeiro append que acontecer depois de vencido o
intervalo**, comparando com o timestamp do proprio log. Nenhuma thread.

## Consequencias

A garantia muda, e a mudanca precisa estar escrita com todas as letras:

> Enquanto houver escrita, no maximo `intervalo` de escritas fica sem `fsync`.
> **Com o log ocioso, nada e sincronizado**: os ultimos registros ficam no page
> cache ate o proximo append, ate `forcarSincronizacao()` ou ate `close()`.

Quem precisa de um teto de tempo real independente de trafego tem duas saidas, as
duas explicitas: chamar `forcarSincronizacao()` de fora, no ritmo que quiser, ou
usar `ACadaAppend`.

A alternativa — thread interna — daria uma garantia mais forte e um projeto menos
honesto sobre o que ele sabe provar. O teste
`DurabilidadeTest#modoPorIntervaloNaoDeveSincronizarSozinhoComOLogOcioso` existe
justamente para fixar essa limitacao: se alguem adicionar a thread depois, esse
teste quebra e a conversa acontece.

## Sobre contar fsync

`Log#quantidadeDeSincronizacoes()` e publico. Nao e um gancho de teste: e a unica
forma de quem opera o log responder "esse modo esta mesmo sincronizando o que eu
acho que sincroniza". Sem isso, os tres modos seriam indistinguiveis de fora — e
uma garantia que ninguem consegue observar nao e uma garantia, e uma promessa.

## Fechar tambem sincroniza, exceto num modo

`close()` chama `fsync` antes de fechar os arquivos — menos no modo `Nenhum`.
Quem escolheu `Nenhum` pediu que o braunlog nunca chamasse `fsync`, e fechar nao e
motivo para desobedecer. Prova:
`DurabilidadeTest#fecharDeveSincronizarExcetoNoModoNenhum`.
