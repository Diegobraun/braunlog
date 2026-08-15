package com.braunlog;

/** Posicao logica de um registro no log. Monotonico, sem lacuna, comecando em zero. */
public record Offset(long valor) implements Comparable<Offset> {

  public static final Offset ZERO = new Offset(0);

  public Offset {
    if (valor < 0) {
      throw new IllegalArgumentException("offset negativo: " + valor);
    }
  }

  public static Offset de(long valor) {
    return new Offset(valor);
  }

  public Offset proximo() {
    return new Offset(valor + 1);
  }

  @Override
  public int compareTo(Offset outro) {
    return Long.compare(valor, outro.valor);
  }

  @Override
  public String toString() {
    return Long.toString(valor);
  }
}
