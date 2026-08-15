# ADR 0003 — Ordem dos campos do registro

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 1

## Contexto

Tres decisoes de layout precisam de justificativa, porque nenhuma delas e obvia
lendo so a tabela do formato:

1. `tamanho` antes de `crc`;
2. `offsetRelativo` em 4 bytes, em vez do offset absoluto em 8;
3. `atributos` reservado, sem bit de tombstone.

## Decisao

Layout: `tamanho`, `crc`, `versaoFormato`, `atributos`, `offsetRelativo`,
`timestamp`, `tamanhoChave`, `chave`, `tamanhoValor`, `valor`.

## Motivos

### `tamanho` antes de `crc`

O leitor precisa saber **quantos bytes verificar** antes de poder verificar
qualquer coisa. Com `tamanho` na frente, a leitura de um registro e:

1. le 4 bytes;
2. decide se ha registro suficiente no arquivo;
3. so entao le e verifica o resto.

Se o CRC viesse primeiro, distinguir "registro cortado no meio" de "registro
corrompido" exigiria ler ate o fim do arquivo e tentar adivinhar a fronteira. A
recuperacao apos queda depende dessa distincao, entao a ordem do formato existe
para servir ao caminho de recuperacao, nao ao de escrita.

O preco e que `tamanho` fica fora da area coberta pelo CRC. Ver ADR 0002 para o
que isso significa e como e testado.

### `offsetRelativo` em 4 bytes

O offset absoluto de um registro e `offsetBase do segmento + offsetRelativo`, e o
offset base vem do **nome do arquivo**. Guardar o absoluto custaria 4 bytes a mais
por registro — 13% do registro minimo — para repetir em todo registro uma
informacao que ja esta no nome do arquivo.

O limite pratico e de 2^31 registros por segmento, muitas ordens de grandeza
acima do que a politica de rolagem por tamanho permite.

Efeito colateral util: um segmento e **relocavel**. Renomear o arquivo muda o
offset base de todos os registros dele sem reescrever um byte. A compactacao da
fase 4 depende disso.

### `atributos` reservado, sem bit de tombstone

A tentacao era marcar tombstone com um bit em `atributos` **e** com
`tamanhoValor == -1`. Duas representacoes do mesmo fato criam um estado
inconsistente possivel — bit ligado com valor presente — que alguem precisaria
validar, testar e explicar.

`tamanhoValor == -1` sozinho ja diz tudo. `atributos` fica reservado com uma
regra dura: **na versao 1 precisa ser zero, e qualquer bit desconhecido faz a
leitura recusar o registro**. Isso transforma o campo num mecanismo de evolucao de
verdade: quando a compressao entrar, um binario antigo vai falhar alto em vez de
devolver bytes comprimidos como se fossem o valor.

## Consequencias

- O cabecalho fixo tem 26 bytes e o registro minimo, 30.
- `docs/formato.md` e a especificacao; esta ADR e o porque.
