# Fase 1 — a decisao dificil foi escrever o formato antes do codigo

A tentacao, num projeto assim, e abrir uma classe `Log`, escrever `anexar` e
`ler`, e deixar o formato binario emergir do que o codigo acabou fazendo. Da
certo por uns dias. Depois voce precisa responder "o que acontece se a maquina
cair no meio de um append?" e descobre que a resposta depende de um detalhe que
ninguem decidiu — foi so o jeito como o `ByteBuffer` ficou.

Escrevemos `docs/formato.md` primeiro. A tabela de campos forcou tres perguntas
que o codigo teria escondido.

## Por que `tamanho` antes de `crc`

Um checksum responde "este dado esta certo?". Ele nao responde "ha dado
suficiente aqui?". Sao perguntas diferentes, e a segunda vem primeiro.

Quando o processo morre no meio de um `write`, o arquivo fica com um prefixo do
registro. Na proxima abertura, o leitor chega naquela posicao e precisa decidir
entre duas acoes muito diferentes: **truncar** (foi uma queda, e normal, siga) ou
**falhar** (o disco corrompeu dado, alguem precisa saber). Com `tamanho` no
inicio, a decisao e aritmetica: se faltam bytes ate o fim do arquivo, foi corte;
se os bytes estao la mas o CRC nao bate, foi corrupcao.

Inverta a ordem e voce nao tem como saber onde o registro terminaria, entao nao
tem como saber se ele esta cortado. E ai a unica saida honesta seria tratar tudo
como corrupcao — o que transformaria uma queda de energia comum num log
inutilizavel.

O preco: `tamanho` fica fora da protecao do CRC. Isso incomoda, e a pergunta
certa e "o que acontece se justamente esses 4 bytes corromperem?". A resposta e
que o registro passa a ser lido com a fronteira errada, o CRC e calculado sobre a
area errada e nao bate — corrupcao detectada. Ou o tamanho corrompido aponta para
alem do fim do arquivo, e vira "parcial" — o registro e descartado. Nunca sai um
registro diferente do que entrou. Nao acreditamos nesse raciocinio: o teste
`deveDetectarCorrupcaoDeQualquerByteSemNuncaDevolverRegistroDiferenteDoEscrito`
inverte **cada byte** de um segmento de amostra, um de cada vez, e verifica que o
que sai da leitura e sempre um prefixo exato do que foi escrito.

## Por que nao tem bit de tombstone

O esboco original tinha um campo `atributos` com um bit para tombstone, e um
`tamanhoValor = -1` que tambem significava tombstone. Duas maneiras de dizer a
mesma coisa. Parece redundancia inofensiva ate voce escrever o decodificador e
perceber que precisa decidir o que fazer quando o bit esta ligado e o valor esta
presente. Toda resposta possivel e ruim.

`tamanhoValor = -1` ficou sendo a unica representacao. E `atributos` virou outra
coisa, mais util: um campo que **precisa ser zero** na versao 1. Qualquer bit
desconhecido faz a leitura recusar o registro. Isso e a diferenca entre um formato
que evolui e um que quebra em silencio — quando a compressao entrar, um binario
antigo vai falhar alto em vez de entregar bytes comprimidos achando que sao o
valor.

## O teste que mudou o desenho

O varredor de registros nasceu como parte do leitor. Quando escrevemos a
recuperacao de abertura, a primeira versao tinha uma segunda copia da mesma
logica — parecida, mas nao igual. Duas implementacoes de "o que e um registro
valido" e uma receita para o dia em que a leitura aceita o que a recuperacao
rejeita.

Viraram uma classe so, `Varredor`, usada pelos dois caminhos. E o motivo de o
resultado ser um `sealed interface` com quatro casos — `Sucesso`, `Fim`,
`Parcial`, `Corrompido` — em vez de uma excecao: quem chama precisa **decidir** o
que fazer com cada caso, e o compilador cobra que os quatro sejam tratados.
