package com.braunlog.formato;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Le um registro por vez de um canal, classificando o que encontra. E o unico ponto do sistema que
 * transforma bytes de arquivo em {@link ResultadoDecodificacao}; leitura normal e recuperacao apos
 * queda usam exatamente o mesmo caminho, e por isso nao podem divergir.
 */
public final class Varredor {

  private final FileChannel canal;
  private final int tamanhoMaximoRegistro;

  public Varredor(FileChannel canal, int tamanhoMaximoRegistro) {
    this.canal = canal;
    this.tamanhoMaximoRegistro = tamanhoMaximoRegistro;
  }

  /**
   * @param posicao byte inicial do registro dentro do arquivo
   * @param limite primeiro byte que a leitura nao pode tocar; e o que garante que um leitor
   *     concorrente nunca enxergue um append ainda em andamento
   */
  public ResultadoDecodificacao ler(long posicao, long limite, long offsetBase) throws IOException {
    long disponivel = limite - posicao;
    if (disponivel <= 0) {
      return new ResultadoDecodificacao.Fim();
    }
    if (disponivel < FormatoRegistro.BYTES_TAMANHO) {
      return new ResultadoDecodificacao.Parcial(disponivel);
    }

    ByteBuffer cabecalho = ByteBuffer.allocate(FormatoRegistro.BYTES_TAMANHO);
    lerExatamente(cabecalho, posicao);
    int tamanho = cabecalho.getInt(0);
    if (tamanho < FormatoRegistro.VALOR_MINIMO_CAMPO_TAMANHO
        || tamanho > tamanhoMaximoRegistro - FormatoRegistro.BYTES_TAMANHO) {
      return new ResultadoDecodificacao.Corrompido("campo tamanho fora da faixa: " + tamanho);
    }
    long tamanhoTotal = (long) tamanho + FormatoRegistro.BYTES_TAMANHO;
    if (disponivel < tamanhoTotal) {
      return new ResultadoDecodificacao.Parcial(disponivel);
    }

    ByteBuffer registro = ByteBuffer.allocate((int) tamanhoTotal);
    registro.putInt(tamanho);
    lerExatamente(registro, posicao);
    registro.flip();
    return FormatoRegistro.decodificar(registro, offsetBase);
  }

  /**
   * @param posicaoDoInicioDoBuffer posicao no arquivo correspondente ao byte zero do buffer; a
   *     posicao corrente do buffer e somada a ela, entao um buffer ja preenchido em parte le a
   *     continuacao certa
   */
  private void lerExatamente(ByteBuffer destino, long posicaoDoInicioDoBuffer) throws IOException {
    while (destino.hasRemaining()) {
      if (canal.read(destino, posicaoDoInicioDoBuffer + destino.position()) < 0) {
        throw new EOFException(
            "arquivo terminou antes dos " + destino.remaining() + " bytes esperados");
      }
    }
  }
}
