package com.braunlog;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.LongSupplier;

import com.braunlog.formato.ResultadoDecodificacao;
import com.braunlog.formato.Varredor;

/**
 * Iterador preguicoso sobre o log. Abre o proprio canal de leitura, entao varios leitores convivem
 * com o escritor sem sincronizacao entre eles.
 *
 * <p>Invariante de concorrencia: nenhuma leitura passa de {@code limiteLegivel}, que so avanca
 * depois de um registro inteiro ter sido escrito. E isso, e nao um lock, que impede um leitor de ver
 * meio registro.
 */
public final class Leitor implements Iterator<RegistroLido>, AutoCloseable {

  private final FileChannel canal;
  private final Varredor varredor;
  private final LongSupplier limiteLegivel;
  private final long offsetBase;
  private final Offset offsetMinimo;

  private long posicao;
  private RegistroLido pendente;

  private Leitor(
      FileChannel canal,
      int tamanhoMaximoRegistro,
      LongSupplier limiteLegivel,
      long offsetBase,
      Offset offsetMinimo) {
    this.canal = canal;
    this.varredor = new Varredor(canal, tamanhoMaximoRegistro);
    this.limiteLegivel = limiteLegivel;
    this.offsetBase = offsetBase;
    this.offsetMinimo = offsetMinimo;
  }

  static Leitor abrir(
      Path arquivo,
      ConfiguracaoLog configuracao,
      LongSupplier limiteLegivel,
      long offsetBase,
      Offset offsetMinimo) {
    try {
      FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.READ);
      return new Leitor(
          canal, configuracao.tamanhoMaximoRegistro(), limiteLegivel, offsetBase, offsetMinimo);
    } catch (IOException e) {
      throw new ErroDeLog("falha ao abrir leitura de " + arquivo, e);
    }
  }

  @Override
  public boolean hasNext() {
    if (pendente == null) {
      pendente = proximoRegistro();
    }
    return pendente != null;
  }

  @Override
  public RegistroLido next() {
    if (!hasNext()) {
      throw new NoSuchElementException("nao ha mais registros a partir do offset " + offsetMinimo);
    }
    RegistroLido registro = pendente;
    pendente = null;
    return registro;
  }

  @Override
  public void close() {
    try {
      canal.close();
    } catch (IOException e) {
      throw new ErroDeLog("falha ao fechar leitor", e);
    }
  }

  private RegistroLido proximoRegistro() {
    try {
      while (true) {
        ResultadoDecodificacao resultado =
            varredor.ler(posicao, limiteLegivel.getAsLong(), offsetBase);
        switch (resultado) {
          case ResultadoDecodificacao.Sucesso sucesso -> {
            posicao += sucesso.bytesConsumidos();
            if (sucesso.registro().offset().compareTo(offsetMinimo) >= 0) {
              return sucesso.registro();
            }
          }
          case ResultadoDecodificacao.Corrompido corrompido ->
              throw new ErroDeCorrupcao(
                  "registro corrompido na posicao " + posicao + ": " + corrompido.motivo());
          case ResultadoDecodificacao.Parcial ignorado -> {
            return null;
          }
          case ResultadoDecodificacao.Fim ignorado -> {
            return null;
          }
        }
      }
    } catch (IOException e) {
      throw new ErroDeLog("falha ao ler na posicao " + posicao, e);
    }
  }
}
