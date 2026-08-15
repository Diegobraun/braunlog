# Fase 4 — o offset que nao pode mudar

Compactar parece uma operacao de faxina: passa, tira o que esta velho, deixa o
arquivo menor. Na pratica, as duas decisoes que importam nao sao sobre o que
apagar — sao sobre o que **nao** apagar, e as duas mudam garantias que ja estavam
escritas no README.

## Renumerar seria mais bonito e estaria errado

Depois de compactar, o segmento fica assim:

```
antes    offset: 0  1  2  3  4  5  6
depois   offset:       3     4  5  6      (0, 1 e 2 viraram lacunas)
```

A tentacao de renumerar para 0, 1, 2, 3 e forte. O log voltaria a ser contiguo, a
garantia "sem lacuna" continuaria valendo, e a tabela do README nao precisaria
mudar.

E ai alguem que guardou "ja processei ate o offset 8.412" volta amanha, pede
`lerDe(8.413)` e recebe um registro completamente diferente do que viria antes da
compactacao. Nao ha erro. Nao ha excecao. Ha reprocessamento silencioso, ou
dados pulados em silencio — que e pior.

O offset e a **unica coordenada estavel** que o braunlog oferece para o mundo
externo. Renumerar quebra o unico contrato que faz o log ser util para quem
precisa retomar de onde parou. Entao os offsets ficam onde estao, o segmento fica
com buracos, e a linha da tabela de garantias mudou de "sem lacuna" para "sem
lacuna ate a primeira compactacao".

Escrever essa linha foi mais trabalhoso do que escrever o compactador. Foi o
trabalho certo.

## O tombstone que nao pode sumir

Segunda tentacao: se a chave `b` foi deletada, por que manter o tombstone dela?
Ele nao tem valor, so ocupa espaco. Apaga junto com os registros antigos e o
segmento fica ainda menor.

Porque o tombstone nao existe para quem ja leu. Ele existe para quem **ainda nao
leu**.

Um consumidor parado no offset 100 vai encontrar, la no 500, a informacao "a chave
b foi removida". Se a compactacao apagar tanto os valores antigos de `b` quanto o
tombstone, esse consumidor nunca ve a delecao — e como os valores antigos tambem
sumiram, ele fica com a versao que tinha em memoria, para sempre. O log passa a
mentir, e mente so para quem esta atrasado, que e o caso mais dificil de
reproduzir num teste.

O Kafka resolve isso com `delete.retention.ms`: mantem o tombstone por tempo
suficiente para qualquer consumidor razoavel passar. Fazer isso aqui exigiria uma
nocao de "consumidor mais atrasado" que este projeto nao tem, entao o tombstone
fica para sempre. O custo — 30 e poucos bytes por chave deletada, eternos — esta
declarado na lista de limitacoes. Preferi um vazamento pequeno e escrito a uma
correcao silenciosa.

## A ordem que so importa quando a luz acaba

A troca do segmento compactado tem tres passos, e a ordem entre eles parece
arbitraria ate voce imaginar a queda de energia no meio:

1. apaga o indice antigo;
2. move o segmento novo por cima do antigo;
3. move o indice novo.

Se a queda cair entre 1 e 2: sobra o segmento antigo sem indice. Abre normal, o
indice e reconstruido varrendo. Sem problema.

Se a queda cair entre 2 e 3: sobra o segmento novo sem indice. Mesma coisa.

Agora inverta os passos 1 e 2. A queda no meio deixa o **segmento novo com o
indice velho**. As entradas daquele indice apontam para posicoes que, no arquivo
novo, caem no meio de um registro. A varredura de abertura comeca ali, le lixo, e
acusa corrupcao num arquivo perfeitamente intacto.

E o mesmo raciocinio de sempre neste projeto: nao basta funcionar, precisa
funcionar quando a energia acaba no pior momento possivel. A diferenca entre as
duas ordens e uma linha de codigo e um incidente.

## Retencao nao tem escolha nenhuma

Depois de tudo isso, retencao foi quase um alivio, porque nao ha decisao a tomar.
"Apagar registros com mais de sete dias" simplesmente nao existe num arquivo
append-only: abrir um buraco no meio obriga a mover todo o resto, invalida todas
as entradas de indice depois dele, quebra a premissa "registro incompleto so
existe no fim" — que sustenta a recuperacao inteira — e faz o chao se mexer
embaixo de um leitor concorrente.

Deletar o arquivo inteiro nao tem nenhum desses problemas.

O que sobra e uma consequencia a explicar: a granularidade da retencao e o
tamanho do segmento. Configurar segmentos de 1 GB e retencao de 100 MB nao
funciona — o log so encolhe de um segmento por vez. Isso nao e limitacao da
implementacao, e da forma do problema; o Kafka tem exatamente a mesma.
