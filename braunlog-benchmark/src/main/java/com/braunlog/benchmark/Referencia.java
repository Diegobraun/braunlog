package com.braunlog.benchmark;

import java.time.Clock;

import com.braunlog.ConfiguracaoLog;

/** Configuracao comum aos benchmarks, para que numeros de arquivos diferentes sejam comparaveis. */
final class Referencia {

  static final int REGISTROS_PARA_LEITURA = 100_000;
  static final int BYTES_DO_VALOR_NA_LEITURA = 256;

  private Referencia() {}

  static ConfiguracaoLog configuracao() {
    return ConfiguracaoLog.padrao(Clock.systemUTC());
  }
}
