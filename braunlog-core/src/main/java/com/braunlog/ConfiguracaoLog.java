package com.braunlog;

import java.time.Clock;
import java.util.Objects;

import com.braunlog.formato.FormatoRegistro;

/**
 * Configuracao de um log. O {@link Clock} entra por parametro para que o timestamp gravado seja
 * controlavel em teste.
 *
 * @param relogio fonte do timestamp de cada registro
 * @param tamanhoMaximoRegistro teto de bytes de um registro codificado; tambem e o limite que
 *     permite classificar um campo {@code tamanho} absurdo como corrupcao em vez de alocar memoria
 *     arbitraria na leitura
 */
public record ConfiguracaoLog(Clock relogio, int tamanhoMaximoRegistro) {

  public static final int TAMANHO_MAXIMO_REGISTRO_PADRAO = 1 << 20;

  public ConfiguracaoLog {
    Objects.requireNonNull(relogio, "relogio");
    if (tamanhoMaximoRegistro < FormatoRegistro.BYTES_MINIMOS_REGISTRO) {
      throw new IllegalArgumentException("tamanho maximo de registro pequeno demais");
    }
  }

  public static ConfiguracaoLog padrao(Clock relogio) {
    return new ConfiguracaoLog(relogio, TAMANHO_MAXIMO_REGISTRO_PADRAO);
  }

  public ConfiguracaoLog comTamanhoMaximoRegistro(int tamanhoMaximoRegistro) {
    return new ConfiguracaoLog(relogio, tamanhoMaximoRegistro);
  }
}
