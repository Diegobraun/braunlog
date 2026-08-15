package com.braunlog;

/**
 * O que uma passada de compactacao fez.
 *
 * @param segmentosReescritos quantos segmentos passaram do dirty ratio e foram trocados
 * @param bytesAntes soma dos bytes desses segmentos antes da reescrita
 * @param bytesDepois soma dos bytes deles depois
 */
public record RelatorioDeCompactacao(int segmentosReescritos, long bytesAntes, long bytesDepois) {

  static final RelatorioDeCompactacao NADA_A_FAZER = new RelatorioDeCompactacao(0, 0, 0);

  public long bytesLiberados() {
    return bytesAntes - bytesDepois;
  }
}
