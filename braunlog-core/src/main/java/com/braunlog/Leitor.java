package com.braunlog;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.braunlog.formato.ResultadoDecodificacao;

/**
 * Iterador preguicoso sobre o log, atravessando segmentos.
 *
 * <p>Invariante de concurrency: nenhuma leitura passa do limite legivel do segmento, que so avanca
 * depois de um registro inteiro ter sido escrito. E isso, e nao um lock, que impede um leitor de ver
 * meio registro. Um leitor que chegou ao fim do segmento ativo enxerga a rolagem na chamada
 * seguinte, entao da para acompanhar o writer sem reabrir o log.
 */
public final class Leitor implements Iterator<RegistroLido>, AutoCloseable {

  private final Log log;
  private final Offset offsetMinimo;

  private Segmento segmento;
  private long posicao;
  private RegistroLido pendente;
  private boolean fechado;

  private Leitor(Log log, Offset offsetMinimo, Segmento segmento, long posicao) {
    this.log = log;
    this.offsetMinimo = offsetMinimo;
    this.segmento = segmento;
    this.posicao = posicao;
  }

  static Leitor abrir(Log log, Offset offsetMinimo) {
    Segmento segmento = log.segmentoQueContem(offsetMinimo.valor());
    return new Leitor(log, offsetMinimo, segmento, segmento.posicaoParaOffset(offsetMinimo.valor()));
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
    fechado = true;
  }

  private RegistroLido proximoRegistro() {
    if (fechado) {
      throw new ErroDeLog("leitor ja fechado");
    }
    try {
      while (true) {
        switch (segmento.ler(posicao)) {
          case ResultadoDecodificacao.Sucesso sucesso -> {
            posicao += sucesso.bytesConsumidos();
            if (sucesso.registro().offset().compareTo(offsetMinimo) >= 0) {
              return sucesso.registro();
            }
          }
          case ResultadoDecodificacao.Corrompido corrompido ->
              throw new ErroDeCorrupcao(
                  "registro corrompido em " + segmento.arquivo() + " na posicao " + posicao + ": "
                      + corrompido.motivo());
          case ResultadoDecodificacao.Parcial ignorado -> {
            return null;
          }
          case ResultadoDecodificacao.Fim ignorado -> {
            Optional<Segmento> proximo = log.segmentoApos(segmento);
            if (proximo.isEmpty()) {
              return null;
            }
            segmento = proximo.get();
            posicao = 0;
          }
        }
      }
    } catch (IOException e) {
      throw new ErroDeLog("falha ao ler " + segmento.arquivo() + " na posicao " + posicao, e);
    }
  }
}
