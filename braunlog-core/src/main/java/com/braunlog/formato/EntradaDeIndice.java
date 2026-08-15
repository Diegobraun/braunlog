package com.braunlog.formato;

/**
 * Ponto de referencia dentro de um segmento: a partir daqui a leitura varre para a frente.
 *
 * @param offsetRelativo offset do registro apontado, relativo ao base do segmento
 * @param posicao byte inicial desse registro no arquivo de segmento
 * @param timestamp epoch millis gravado nesse registro
 */
public record EntradaDeIndice(int offsetRelativo, int posicao, long timestamp) {

  public static final EntradaDeIndice INICIO = new EntradaDeIndice(0, 0, Long.MIN_VALUE);
}
