# ADR 0002 — CRC32C por registro

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 1

## Contexto

Cada registro precisa de um campo de verificacao que permita a leitura afirmar
"este dado nao e o que foi escrito". As opcoes praticas no JDK, sem dependencia:
`CRC32` (polinomio IEEE 802.3), `CRC32C` (polinomio Castagnoli, `java.util.zip`
desde o Java 9), ou um hash criptografico via `MessageDigest`.

## Decisao

**CRC32C**, 4 bytes, cobrindo do campo `versaoFormato` ate o ultimo byte do
registro.

## Motivos

1. **Aceleracao em hardware.** O polinomio Castagnoli tem instrucao dedicada em
   x86-64 (`crc32`, desde SSE4.2) e em ARMv8. A implementacao do JDK usa intrinsic
   nessas plataformas. O CRC32 IEEE nao tem esse tratamento e roda por tabela.
2. **E o que o ecossistema usa.** Kafka trocou CRC32 por CRC32C na versao 0.11,
   pelo mesmo motivo. iSCSI, SCTP e ext4 tambem usam Castagnoli.
3. **Deteccao de erro e o objetivo; seguranca nao e.** O adversario aqui e o
   disco, nao uma pessoa. Um hash criptografico custa mais de dez vezes mais por
   byte para proteger contra uma ameaca que este projeto declara fora de escopo.
4. **4 bytes num registro minimo de 30 ja custam 13%.** Subir para 8 ou 32 bytes
   de checksum pioraria muito o piso, e registros pequenos sao o caso comum de um
   log de eventos.

## O que o CRC nao cobre, de proposito

O campo `tamanho` fica **fora** da area verificada, porque e ele que delimita a
area a verificar. A consequencia esta documentada: corromper `tamanho` leva a
leitura a classificar o registro como corrompido — por divergencia de CRC sobre a
area errada — ou como parcial, se o tamanho corrompido passar do fim do arquivo.
Em nenhum dos casos um registro diferente do escrito e devolvido. E isso que o
teste `RecuperacaoTest#deveDetectarCorrupcaoDeQualquerByteSemNuncaDevolverRegistroDiferenteDoEscrito`
verifica, byte a byte.

## Consequencias

- Um erro de 4 bytes ou menos em rajada e sempre detectado; erros maiores escapam
  com probabilidade da ordem de 2^-32. Para um log embarcado, e o padrao da
  industria.
- CRC32C nao corrige nada. Corrupcao vira erro, nao recuperacao.
