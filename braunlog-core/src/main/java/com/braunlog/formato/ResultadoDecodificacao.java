package com.braunlog.formato;

import com.braunlog.RegistroLido;

/**
 * Resultado de tentar ler um registro numa posicao do arquivo.
 *
 * <p>A separacao entre {@link Parcial} e {@link Corrompido} e a decisao central da recuperacao:
 * parcial e o rastro esperado de uma queda no meio de um append e leva a truncar; corrompido e dado
 * estragado e nunca pode ser devolvido como se fosse valido.
 */
public sealed interface ResultadoDecodificacao {

  record Sucesso(RegistroLido registro, int bytesConsumidos) implements ResultadoDecodificacao {}

  /** Nao ha mais nenhum byte a partir da posicao pedida. */
  record Fim() implements ResultadoDecodificacao {}

  /** Ha bytes, mas menos do que um registro completo. */
  record Parcial(long bytesDisponiveis) implements ResultadoDecodificacao {}

  record Corrompido(String motivo) implements ResultadoDecodificacao {}
}
