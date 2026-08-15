# Fase 5 — o que um mutante sobrevivente esta tentando dizer

A ultima fase era para ser sobre performance. Acabou sendo, em boa parte, sobre
duas ferramentas que passaram o projeto inteiro dizendo coisas que eu demorei
para escutar: o relatorio de mutacao e a contagem de linhas.

## Mutante sobrevivente nem sempre e teste fraco

O manual diz que um mutante que sobrevive indica asserção fraca. Na maior parte
das vezes e verdade, e foi assim que varios testes deste projeto ficaram melhores.
Mas houve tres categorias em que o mutante sobrevivente estava dizendo outra
coisa.

**O mutante que descreve o desenho.** O PIT trocou `posicaoParaOffset` por
"devolve sempre zero" e a suite inteira continuou verde. Isso nao e teste fraco —
e a definicao do indice esparso. Com zero, toda leitura varre desde o inicio do
segmento e devolve exatamente a mesma resposta, so mais devagar. O relatorio
estava dizendo: *voce construiu uma otimizacao, e otimizacao nao tem teste de
correcao*. A resposta certa nao foi reforcar os testes de leitura, foi criar um
teste separado so para a dica do indice.

**O mutante equivalente.** `inicio + DESLOCAMENTO_TAMANHO` virou
`inicio - DESLOCAMENTO_TAMANHO`, e como a constante e zero, os dois programas sao
identicos. Nenhum teste pode matar esse mutante, porque nao ha diferenca a
observar. Perseguir esse numero seria trocar clareza por metrica.

**O mutante que exige um mock.** Remover a chamada a `FileChannel.force` nao muda
nada observavel de dentro do processo — e justamente por isso a garantia de
durabilidade precisou de `quantidadeDeSincronizacoes()` como contador publico. O
mutante que resta e o de dentro dessa contagem, e ele so morreria com um
`FileChannel` falso.

Fechei em 90% de mutacao, com meta de 85%. Os que sobraram estao nessas tres
gavetas, e cada uma delas ensina algo sobre o codigo — o que e mais util do que um
numero redondo.

## Contar linhas funcionou

O alvo era 800 a 1.500 linhas de codigo de producao. A contagem por fase:

| Fase | Linhas | O que entrou |
|---|---|---|
| 1 | 543 | formato, codec, append e leitura num arquivo |
| 2 | 890 | segmentos, indice esparso, busca por tempo |
| 3 | 974 | tres modos de durabilidade, fsync de diretorio |
| 4 | 1.287 | retencao e compactacao |
| 5 | 1.279 | remocoes da revisao final |

A fase 4 foi a que quase estourou, e o aviso veio antes do problema: quando um
recurso novo custa 300 linhas num nucleo de mil, ou o recurso e grande demais ou a
abstracao esta errada. No caso da compactacao, era grande mesmo — mas a contagem
me fez recusar duas ideias pelo caminho: um cache de descritores de arquivo e uma
politica de compactacao configuravel por estrategia. As duas seriam abstracao
criada para um futuro que nao existe.

A revisao final tirou pouco: um metodo publico que ninguem chamava (`contem`), um
`vazio()` que era `quantidadeDeEntradas() == 0` com outro nome, e dois metodos de
fsync de diretorio que eram um so. Menos do que eu esperava — sinal de que a
disciplina funcionou durante o caminho, e nao no fim.

## O benchmark que nao publiquei

A suite JMH ficou pronta e nao rodou. Quando fui medir, a maquina de referencia
estava ocupada com outro benchmark, e numero colhido com a CPU disputada nao e
numero — e ruido com unidade.

Tive a tentacao de rodar assim mesmo e escrever "medido em MacBook M2 Pro". Seria
igual a declarar uma garantia sem teste: a tabela pareceria mais completa e diria
menos. A secao ficou com a metodologia, a forma esperada de cada curva e a
declaracao explicita de que os numeros vem quando a maquina estiver em repouso.

O aviso que abre a secao vale por ela inteira: estes numeros existem para mostrar
que o `fsync` domina tudo, que o lote dilui esse custo e que o intervalo do indice
troca memoria por varredura. Comparar com o Kafka nao seria so injusto — seria
comparar coisas que resolvem problemas diferentes.
