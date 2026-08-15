package com.braunlog;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class Suporte {

  public static final long INSTANTE_FIXO = 1_700_000_000_000L;

  private Suporte() {}

  public static Clock relogioFixo() {
    return Clock.fixed(Instant.ofEpochMilli(INSTANTE_FIXO), ZoneOffset.UTC);
  }

  public static ConfiguracaoLog configuracaoPadrao() {
    return ConfiguracaoLog.padrao(relogioFixo());
  }

  public static byte[] bytes(String texto) {
    return texto.getBytes(StandardCharsets.UTF_8);
  }

  public static Registro registro(String chave, String valor) {
    return Registro.de(bytes(chave), bytes(valor));
  }
}
