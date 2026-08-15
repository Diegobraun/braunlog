# ADR 0007 — fsync do diretorio quando um segmento nasce

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 3

## Contexto

`fsync` num arquivo grava o conteudo daquele arquivo. Ele **nao** grava a entrada
do arquivo no diretorio que o contem — isso e outro bloco de metadados, e o
sistema de arquivos pode adiar essa escrita.

Consequencia pratica: um segmento recem-criado, com registros ja sincronizados,
pode simplesmente nao existir depois de uma queda de energia. O arquivo estava
duravel; o nome dele, nao.

E uma das pegadinhas mais conhecidas de quem escreve banco de dados, e uma das
menos conhecidas de quem so usa.

## Decisao

Na rolagem de segmento, quando o modo de durabilidade nao for `Nenhum`:

1. `fsync` no segmento que acabou de ser fechado;
2. `fsync` no **diretorio** do log, para gravar a entrada do segmento novo.

O segundo passo abre o proprio diretorio como `FileChannel` em modo leitura e
chama `force(true)`.

## Consequencias

- Rolagem custa dois `fsync` a mais. Rolagem acontece uma vez por segmento —
  64 MiB por padrao — entao o custo por registro e irrelevante.
- **Nem todo sistema de arquivos deixa abrir um diretorio como canal.** No
  Windows, `FileChannel.open` sobre um diretorio lanca excecao. Ali essa falha e
  engolida de proposito: a entrada de diretorio ja e gravada por outro mecanismo,
  e derrubar um append por causa disso seria trocar um risco raro por uma falha
  certa. E o unico `catch` vazio do projeto, e ele tem javadoc explicando por que.
- No modo `Nenhum` nada disso acontece, coerente com o contrato daquele modo.

## O que continua nao coberto

O braunlog nao sincroniza o diretorio quando **deleta** um segmento — isso entra
na fase 4, junto com retencao, porque e o mesmo problema ao contrario: o arquivo
some, mas a entrada de diretorio pode voltar depois de uma queda.
