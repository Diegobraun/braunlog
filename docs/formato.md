# Formato binario do braunlog

Este documento e normativo: o codigo segue esta especificacao, nao o contrario.
Se houver divergencia entre este arquivo e a implementacao, o bug esta no codigo.

- **Endianness**: big-endian em todos os campos numericos. E o padrao do
  `ByteBuffer` do JDK e o mesmo do Kafka; evita depender de configuracao.
- **Versao atual do layout de registro**: `1`.
- **Inteiros com sinal**: todos. `-1` e usado como marcador de "campo ausente",
  por isso nenhum campo de tamanho pode ser interpretado como sem sinal.

---

## 1. Registro

Um registro e a unidade atomica do log. Os deslocamentos abaixo sao relativos ao
primeiro byte do registro dentro do arquivo de segmento.

| Deslocamento | Campo | Bytes | Descricao |
|---|---|---|---|
| 0 | `tamanho` | 4 | numero de bytes do registro **depois** deste campo |
| 4 | `crc` | 4 | CRC32C dos bytes de `versaoFormato` (deslocamento 8) ate o fim do registro |
| 8 | `versaoFormato` | 1 | versao deste layout; `1` hoje |
| 9 | `atributos` | 1 | bitmap de flags; **todos os bits reservados na v1, valor obrigatorio `0`** |
| 10 | `offsetRelativo` | 4 | offset do registro menos o offset base do segmento |
| 14 | `timestamp` | 8 | epoch millis do momento do append, vindo do `Clock` injetado |
| 22 | `tamanhoChave` | 4 | numero de bytes da chave, ou `-1` quando o registro nao tem chave |
| 26 | `chave` | `tamanhoChave` | bytes da chave; ausente quando `tamanhoChave == -1` |
| 26 + max(0, `tamanhoChave`) | `tamanhoValor` | 4 | numero de bytes do valor, ou `-1` para **tombstone** |
| ... | `valor` | `tamanhoValor` | bytes do valor; ausente quando `tamanhoValor == -1` |

Derivados:

- Cabecalho fixo ate `tamanhoChave` inclusive: **26 bytes**.
- Registro minimo (sem chave e sem valor nao e permitido, mas o piso aritmetico e):
  26 + 4 = **30 bytes**.
- `tamanho` = 26 + max(0, `tamanhoChave`) + max(0, `tamanhoValor`) - 4.
- Area coberta pelo CRC = `[8, 4 + tamanho)`.

### Tombstone e registro sem chave

| `tamanhoChave` | `tamanhoValor` | Significado |
|---|---|---|
| >= 0 | >= 0 | par chave/valor normal |
| `-1` | >= 0 | registro sem chave; nunca participa de compactacao |
| >= 0 | `-1` | **tombstone**: marca a chave como removida |
| `-1` | `-1` | invalido; o codificador recusa e o decodificador trata como corrompido |

Tombstone e codificado **apenas** por `tamanhoValor == -1`. Nao existe bit
redundante em `atributos` para a mesma informacao: duas fontes de verdade para o
mesmo fato sao uma fonte de bug, nao uma otimizacao.

---

## 2. Deteccao de registro parcial

Um append nao e atomico no nivel do sistema de arquivos: uma queda pode deixar
qualquer prefixo do registro no disco. A leitura, em qualquer posicao `p` do
arquivo com limite legivel `L`, aplica esta sequencia:

1. `L - p == 0` -> **fim** do fluxo.
2. `L - p < 4` -> **parcial**: nao ha nem o campo `tamanho` completo.
3. Le `tamanho`. Se `tamanho < 26` ou `tamanho > tamanhoMaximoRegistro` ->
   **corrompido**: nenhum registro valido tem esse tamanho.
4. `L - p < 4 + tamanho` -> **parcial**: o registro foi cortado no meio.
5. Recalcula o CRC32C da area coberta. Divergencia -> **corrompido**.
6. Valida `versaoFormato`, `atributos`, `offsetRelativo >= 0` e a aritmetica dos
   blocos de chave e valor. Divergencia -> **corrompido**.

A distincao importa: **parcial** e o resultado esperado de uma queda e leva a
truncar o arquivo naquele ponto na abertura; **corrompido** e dado estragado e e
levantado como erro para quem le. Ver `docs/diagramas/04-recuperacao.md`.

O campo `tamanho` vir antes do `crc` e o que torna o passo 4 possivel sem ler o
registro inteiro. Ver `docs/adr/0003-ordem-dos-campos-do-registro.md`.

---

## 3. Arquivo de segmento

Nome: `<offsetBase>.log`, com o offset base em decimal, preenchido com zeros a
esquerda ate **20 digitos** (o maior `long` tem 19 digitos, entao 20 cobre todos
os casos e mantem a ordenacao lexicografica igual a ordenacao numerica).

```
00000000000000000000.log    <- segmento que comeca no offset 0
00000000000000010423.log    <- segmento que comeca no offset 10423
```

Por que o offset base no nome, e nao dentro do arquivo:

- Localizar o segmento que contem um offset e uma busca binaria sobre a listagem
  do diretorio, sem abrir nenhum arquivo.
- `offsetRelativo` cabe em 4 bytes porque o base vem do nome. Guardar o offset
  absoluto custaria 4 bytes a mais por registro.
- Um segmento pode ser copiado, movido ou deletado inteiro sem reescrever nada.

Um arquivo de segmento e uma sequencia de registros concatenados, sem cabecalho
de arquivo e sem rodape. Isso e proposital: um arquivo sem cabecalho pode ser
concatenado, truncado e lido a partir de qualquer posicao de registro conhecida.

---

## 4. Entrada de indice esparso

Formato definido aqui e implementado na Fase 2. Arquivo `<offsetBase>.indice`.

| Deslocamento | Campo | Bytes | Descricao |
|---|---|---|---|
| 0 | `offsetRelativo` | 4 | offset relativo ao base do segmento |
| 4 | `posicao` | 4 | posicao em bytes do inicio do registro no arquivo de segmento |

Entrada de 8 bytes, tamanho fixo, sem CRC. Duas consequencias:

- A n-esima entrada esta em `n * 8`; a busca binaria e aritmetica pura.
- O indice e **descartavel**. Ele nunca e a fonte de verdade: e sempre possivel
  reconstrui-lo varrendo o segmento. Por isso nao carrega CRC e nao precisa de
  fsync. Se estiver corrompido ou truncado, e reconstruido na abertura.

Indice de tempo: arquivo `<offsetBase>.tempo`, mesma logica.

| Deslocamento | Campo | Bytes | Descricao |
|---|---|---|---|
| 0 | `timestamp` | 8 | epoch millis |
| 8 | `offsetRelativo` | 4 | primeiro offset com timestamp >= este |

---

## 5. Regra de evolucao do formato

O compromisso e: **um arquivo escrito por uma versao antiga continua legivel, e
um arquivo escrito por uma versao nova nunca e lido errado por um binario
antigo.** A segunda metade e a que costuma ser esquecida.

1. **Campo novo opcional** — reserva um bit em `atributos`, escreve os bytes
   extras no fim do registro (depois de `valor`) e mantem `versaoFormato`. Um
   leitor antigo ve um bit desconhecido em `atributos` e falha alto, porque a v1
   exige `atributos == 0`. Falhar alto e melhor do que devolver um registro com
   metade do significado.
2. **Mudanca incompativel de layout** — incrementa `versaoFormato`. O leitor
   despacha a decodificacao por versao e recusa versao maior do que a que
   conhece. Registros de versoes diferentes podem coexistir no mesmo segmento.
3. **`tamanho` sempre delimita o registro.** Um leitor que nao entende um
   registro ainda sabe onde ele termina e pode pular para o proximo sem perder o
   sincronismo do fluxo.
4. **Nada e removido de posicao fixa.** Campos obsoletos viram bytes ignorados,
   nunca buracos que deslocam os demais.

O que **nao** e suportado: reescrever registros ja gravados para migrar de
formato. O log e append-only; migracao acontece na compactacao, que reescreve
segmentos inteiros na versao corrente.
