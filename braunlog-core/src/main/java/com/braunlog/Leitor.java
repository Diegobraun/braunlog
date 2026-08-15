package com.braunlog;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
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
 *
 * <p>Compactacao e retencao trocam ou apagam segmentos embaixo de leitores em andamento. Quando isso
 * acontece, a leitura do canal fechado falha e o leitor se reposiciona pelo proximo offset que
 * esperava — continua de onde parava, no arquivo que estiver valendo agora.
 */
public final class Leitor implements Iterator<RegistroLido>, AutoCloseable {

  private final Log log;
  private final Offset offsetMinimo;

  private Segmento segmento;
  private long posicao;
  private long proximoOffsetEsperado;
  private RegistroLido pendente;
  private boolean fechado;

  private Leitor(Log log, Offset offsetMinimo, Segmento segmento, long posicao) {
    this.log = log;
    this.offsetMinimo = offsetMinimo;
    this.segmento = segmento;
    this.posicao = posicao;
    this.proximoOffsetEsperado = offsetMinimo.valor();
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
    while (true) {
      try {
        return varrer();
      } catch (ClosedChannelException segmentoTrocadoOuApagado) {
        if (!reposicionar()) {
          throw new ErroDeLog(
              "segmento " + segmento.arquivo() + " foi fechado enquanto o leitor o usava",
              segmentoTrocadoOuApagado);
        }
      } catch (IOException e) {
        throw new ErroDeLog("falha ao ler " + segmento.arquivo() + " na posicao " + posicao, e);
      }
    }
  }

  private RegistroLido varrer() throws IOException {
    while (true) {
      switch (segmento.ler(posicao)) {
        case ResultadoDecodificacao.Sucesso sucesso -> {
          posicao += sucesso.bytesConsumidos();
          RegistroLido lido = sucesso.registro();
          if (lido.offset().compareTo(offsetMinimo) >= 0) {
            proximoOffsetEsperado = lido.offset().valor() + 1;
            return lido;
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
  }

  /**
   * Reaponta para o segmento que hoje contem o offset esperado. Devolve falso quando o segmento
   * continua sendo o mesmo — ai o canal fechado nao foi troca de arquivo, foi o log fechando.
   */
  private boolean reposicionar() {
    Segmento atual = log.segmentoQueContem(proximoOffsetEsperado);
    if (atual == segmento) {
      return false;
    }
    segmento = atual;
    posicao = atual.posicaoParaOffset(proximoOffsetEsperado);
    return true;
  }
}
