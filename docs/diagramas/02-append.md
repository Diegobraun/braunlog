# 02 — O caminho de um append

```mermaid
sequenceDiagram
    autonumber
    participant Cliente
    participant Log
    participant Segmento as Segmento ativo
    participant Indice as Indice esparso
    participant SO as Page cache do SO
    participant Disco

    Cliente->>Log: anexar(registro)
    Log->>Log: tamanho codificado <= tamanhoMaximoRegistro?
    Log->>Log: cabe no segmento ativo?
    alt nao cabe
        Log->>Segmento: fecha para escrita
        Log->>Log: cria segmento com offsetBase = proximoOffset
    end
    Log->>Log: timestamp = max(relogio, ultimoTimestamp)
    Log->>Segmento: anexar(registro, timestamp)
    Segmento->>Segmento: codifica em ByteBuffer e calcula CRC32C
    Segmento->>SO: write(buffer, posicao)
    SO-->>Segmento: bytes escritos
    Segmento->>Segmento: limiteLegivel = posicao + tamanho
    Note right of Segmento: publicacao: so agora um leitor<br/>enxerga este registro
    Segmento->>Indice: anota se cresceu o intervalo
    Segmento-->>Log: offset
    Log-->>Cliente: offset

    rect rgb(245, 240, 225)
        Note over SO,Disco: fsync e uma decisao separada
        Cliente->>Log: forcarSincronizacao()
        Log->>SO: force(true)
        SO->>Disco: grava de verdade
    end
```

## O que a ordem dos passos garante

A escrita completa **antes** de `limiteLegivel` avancar. Como nenhum leitor le
alem desse limite, nao existe janela em que um leitor veja meio registro. Nao ha
lock entre writer e leitores; ha uma unica escrita `volatile` que publica o
trabalho ja terminado.

A entrada de indice e anotada **depois** da publicacao. Se o processo cair entre
as duas coisas, o indice fica sem uma entrada — e o segmento continua correto,
porque o indice e so uma dica.

## O que ainda nao esta aqui

`anexar` retorna assim que o `write` volta, ou seja, quando os bytes estao no page
cache do sistema operacional — nao no disco. Isso significa que **o registro
sobrevive a uma queda do processo, mas nao a uma queda do sistema operacional**,
a menos que `forcarSincronizacao` tenha sido chamado.

Os tres modos de durabilidade — `ACadaAppend`, `PorIntervalo`, `Nenhum` — e a
garantia exata de cada um entram na fase 3, com o diagrama de recuperacao.
