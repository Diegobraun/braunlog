package com.braunlog;

/**
 * Dado no disco nao corresponde ao que foi escrito. Nunca e lancado para escrita parcial: registro
 * cortado no meio e situacao esperada de queda e e tratado por truncamento, nao por erro.
 */
public final class ErroDeCorrupcao extends ErroDeLog {

  public ErroDeCorrupcao(String mensagem) {
    super(mensagem);
  }
}
