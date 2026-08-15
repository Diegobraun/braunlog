# 01 — Anatomia do log

Um log e um diretorio de arquivos de segmento. Cada segmento e uma sequencia de
registros concatenados. Um unico segmento esta aberto para escrita — o **ativo**;
os demais estao fechados e sao imutaveis.

```mermaid
flowchart TB
  subgraph diretorio["diretorio do log"]
    direction TB
    subgraph fechado0["00000000000000000000.log — fechado"]
      r0["offset 0"] --- r1["offset 1"] --- r2["offset 2"]
    end
    subgraph fechado1["00000000000000000003.log — fechado"]
      r3["offset 3"] --- r4["offset 4"]
    end
    subgraph ativo["00000000000000000005.log — ativo"]
      r5["offset 5"] --- r6["offset 6"] --- livre["espaco livre"]
    end
  end

  escritor(["escritor unico"]) -->|anexa no fim| livre
  leitor(["leitor"]) -->|le de qualquer offset| r1

  style ativo fill:#eef7ee,stroke:#4c8c4a
  style livre fill:#ffffff,stroke:#bbbbbb,stroke-dasharray: 4 4
```

Na fase 1 existe apenas o segmento `00000000000000000000.log`. A segmentacao
entra na fase 2 sem mudar a API publica.

## Registro por dentro

```mermaid
flowchart LR
  t["tamanho<br/>4 bytes"] --> c["crc<br/>4 bytes"] --> v["versao<br/>1"] --> a["atributos<br/>1"]
  a --> o["offsetRelativo<br/>4"] --> ts["timestamp<br/>8"] --> tc["tamanhoChave<br/>4"]
  tc --> ch["chave<br/>variavel"] --> tv["tamanhoValor<br/>4"] --> val["valor<br/>variavel"]

  style t fill:#fdf3e0,stroke:#c08b2a
  style c fill:#fdf3e0,stroke:#c08b2a
```

Os dois campos destacados sao os unicos que a leitura consulta antes de confiar em
qualquer byte: `tamanho` diz onde o registro termina, `crc` diz se ele chegou
inteiro. Tudo a partir de `versao` esta coberto pelo CRC.

## Invariantes

| Invariante | Por que importa |
|---|---|
| Offset e monotonico e sem lacuna | um leitor sabe que `n+1` vem depois de `n` sem consultar indice |
| Segmento fechado nunca muda | leitor de segmento fechado nao precisa se coordenar com o escritor |
| Escrita so acontece no fim do segmento ativo | nao existe atualizacao em lugar, entao nao existe registro meio-escrito no meio do arquivo |
| Nenhuma leitura passa do limite legivel | o limite so avanca depois de um registro inteiro no disco, entao leitor concorrente nunca ve meio registro |
