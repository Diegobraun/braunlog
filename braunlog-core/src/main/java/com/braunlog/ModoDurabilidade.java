package com.braunlog;

import java.time.Duration;
import java.util.Objects;

/**
 * Quando o log chama {@code fsync}. Cada modo tem uma garantia diferente, e as duas quedas
 * possiveis nao sao a mesma coisa: matar o processo deixa o page cache do sistema operacional
 * intacto; derrubar o sistema operacional nao.
 */
public sealed interface ModoDurabilidade {

  ModoDurabilidade A_CADA_APPEND = new ACadaAppend();
  ModoDurabilidade NENHUM = new Nenhum();

  /** {@code fsync} antes de {@code anexar} retornar. Nada confirmado se perde. */
  record ACadaAppend() implements ModoDurabilidade {}

  /**
   * {@code fsync} no primeiro append que acontecer depois de passado o intervalo. Nao existe thread
   * de background: um log ocioso nao sincroniza sozinho.
   */
  record PorIntervalo(Duration intervalo) implements ModoDurabilidade {
    public PorIntervalo {
      Objects.requireNonNull(intervalo, "intervalo");
      if (intervalo.isNegative() || intervalo.isZero()) {
        throw new IllegalArgumentException("intervalo precisa ser positivo: " + intervalo);
      }
    }
  }

  /** Nenhum {@code fsync}. Sobrevive a queda do processo, nao a queda do sistema operacional. */
  record Nenhum() implements ModoDurabilidade {}

  static ModoDurabilidade porIntervalo(Duration intervalo) {
    return new PorIntervalo(intervalo);
  }
}
