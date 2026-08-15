package com.braunlog;

import java.util.Arrays;

/** Registro devolvido pela leitura, ja com o offset atribuido e o timestamp gravado. */
public record RegistroLido(Offset offset, long timestamp, byte[] chave, byte[] valor) {

  public boolean ehTombstone() {
    return valor == null;
  }

  public boolean temChave() {
    return chave != null;
  }

  public Registro registro() {
    return new Registro(chave, valor);
  }

  @Override
  public boolean equals(Object outro) {
    return outro instanceof RegistroLido lido
        && offset.equals(lido.offset)
        && timestamp == lido.timestamp
        && Arrays.equals(chave, lido.chave)
        && Arrays.equals(valor, lido.valor);
  }

  @Override
  public int hashCode() {
    int resultado = offset.hashCode();
    resultado = 31 * resultado + Long.hashCode(timestamp);
    resultado = 31 * resultado + Arrays.hashCode(chave);
    return 31 * resultado + Arrays.hashCode(valor);
  }

  @Override
  public String toString() {
    return "RegistroLido[offset="
        + offset
        + ", timestamp="
        + timestamp
        + ", chave="
        + Arrays.toString(chave)
        + ", valor="
        + Arrays.toString(valor)
        + "]";
  }
}
