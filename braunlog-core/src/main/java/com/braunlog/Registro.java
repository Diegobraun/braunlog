package com.braunlog;

import java.util.Arrays;

/**
 * Registro a ser anexado ao log.
 *
 * <p>Os arrays passados aqui nao sao copiados: o registro assume a posse deles. Mutar um array
 * depois de construir o registro e comportamento indefinido.
 *
 * <p>{@code chave} nula significa registro sem chave. {@code valor} nulo significa tombstone.
 */
public record Registro(byte[] chave, byte[] valor) {

  public Registro {
    if (chave == null && valor == null) {
      throw new IllegalArgumentException("registro sem chave e sem valor nao tem significado");
    }
  }

  public static Registro de(byte[] chave, byte[] valor) {
    return new Registro(chave, valor);
  }

  public static Registro semChave(byte[] valor) {
    return new Registro(null, valor);
  }

  public static Registro tombstone(byte[] chave) {
    return new Registro(chave, null);
  }

  public boolean ehTombstone() {
    return valor == null;
  }

  public boolean temChave() {
    return chave != null;
  }

  @Override
  public boolean equals(Object outro) {
    return outro instanceof Registro registro
        && Arrays.equals(chave, registro.chave)
        && Arrays.equals(valor, registro.valor);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(chave) + Arrays.hashCode(valor);
  }

  @Override
  public String toString() {
    return "Registro[chave=" + Arrays.toString(chave) + ", valor=" + Arrays.toString(valor) + "]";
  }
}
