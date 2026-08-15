package com.braunlog;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.locks.LockSupport;

/**
 * Escreve no log e trava de proposito, sem nunca fechar, para que o processo pai possa mata-lo com
 * SIGKILL. E a unica forma honesta de testar queda de processo: matar de verdade um processo de
 * verdade.
 */
public final class EscritorEmProcessoSeparado {

  static final String AVISO_DE_PRONTO = "pronto";

  private EscritorEmProcessoSeparado() {}

  public static void main(String[] argumentos) {
    java.nio.file.Path diretorio = java.nio.file.Path.of(argumentos[0]);
    int quantidade = Integer.parseInt(argumentos[1]);
    ModoDurabilidade modo =
        argumentos[2].equals("nenhum") ? ModoDurabilidade.NENHUM : ModoDurabilidade.A_CADA_APPEND;

    Clock relogio = Clock.fixed(Instant.ofEpochMilli(Long.parseLong(argumentos[3])), ZoneOffset.UTC);
    Log log =
        Log.abrir(diretorio, ConfiguracaoLog.padrao(relogio).comModoDurabilidade(modo));
    for (int i = 0; i < quantidade; i++) {
      log.anexar(
          Registro.de(
              ("chave-" + i).getBytes(StandardCharsets.UTF_8),
              ("valor-" + i).getBytes(StandardCharsets.UTF_8)));
    }

    System.out.println(AVISO_DE_PRONTO);
    System.out.flush();
    while (true) {
      LockSupport.park();
    }
  }
}
