package com.braunlog;

/** Falha ao operar o log. Envolve {@link java.io.IOException} para manter a API sem checked. */
public class ErroDeLog extends RuntimeException {

  public ErroDeLog(String mensagem) {
    super(mensagem);
  }

  public ErroDeLog(String mensagem, Throwable causa) {
    super(mensagem, causa);
  }
}
