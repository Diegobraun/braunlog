package com.braunlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.braunlog.formato.FormatoRegistro;

/**
 * Commit log append-only, com um unico writer e varios leitores.
 *
 * <p>O log e um diretorio de segmentos. Escrever e sempre acrescentar no fim do segmento ativo; ler
 * e varrer para a frente a partir de um ponto que o indice esparso indicou.
 */
public final class Log implements AutoCloseable {

  private final Path diretorio;
  private final ConfiguracaoLog configuracao;
  private final CopyOnWriteArrayList<Segmento> segmentos = new CopyOnWriteArrayList<>();

  private volatile Segmento ativo;
  private long ultimoTimestamp;

  private Log(Path diretorio, ConfiguracaoLog configuracao) {
    this.diretorio = diretorio;
    this.configuracao = configuracao;
  }

  public static Log abrir(Path diretorio, ConfiguracaoLog configuracao) {
    Objects.requireNonNull(diretorio, "diretorio");
    Objects.requireNonNull(configuracao, "configuracao");
    Log log = new Log(diretorio, configuracao);
    try {
      Files.createDirectories(diretorio);
      log.abrirSegmentos(offsetsBaseExistentes(diretorio));
      return log;
    } catch (IOException e) {
      throw new ErroDeLog("falha ao abrir log em " + diretorio, e);
    }
  }

  public synchronized Offset anexar(Registro registro) {
    Objects.requireNonNull(registro, "registro");
    int tamanho = FormatoRegistro.tamanhoCodificado(registro);
    if (tamanho > configuracao.tamanhoMaximoRegistro()) {
      throw new ErroDeLog(
          "registro de " + tamanho + " bytes excede o maximo de "
              + configuracao.tamanhoMaximoRegistro());
    }
    try {
      if (ativo.bytes() > 0 && ativo.bytes() + tamanho > configuracao.bytesMaximosPorSegmento()) {
        rolar();
      }
      long timestamp = Math.max(configuracao.relogio().millis(), ultimoTimestamp);
      Offset offset = ativo.anexar(registro, timestamp);
      ultimoTimestamp = timestamp;
      return offset;
    } catch (IOException e) {
      throw new ErroDeLog("falha ao anexar em " + diretorio, e);
    }
  }

  public Leitor lerDe(Offset offset) {
    Objects.requireNonNull(offset, "offset");
    return Leitor.abrir(this, offset);
  }

  /** Leitor posicionado no primeiro registro com timestamp maior ou igual ao instante pedido. */
  public Leitor lerDesde(Instant instante) {
    return lerDe(primeiroOffsetDesde(instante).orElseGet(this::proximoOffset));
  }

  public Optional<Offset> primeiroOffsetDesde(Instant instante) {
    Objects.requireNonNull(instante, "instante");
    long alvo = instante.toEpochMilli();
    try {
      for (Segmento segmento : segmentos) {
        Optional<Offset> achado = segmento.primeiroOffsetComTimestamp(alvo);
        if (achado.isPresent()) {
          return achado;
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      throw new ErroDeLog("falha ao buscar por tempo em " + diretorio, e);
    }
  }

  public Optional<Offset> ultimoOffset() {
    long proximo = ativo.proximoOffset();
    return proximo == primeiroOffsetDoLog() ? Optional.empty() : Optional.of(Offset.de(proximo - 1));
  }

  public Offset proximoOffset() {
    return Offset.de(ativo.proximoOffset());
  }

  public int quantidadeDeSegmentos() {
    return segmentos.size();
  }

  public void forcarSincronizacao() {
    try {
      ativo.sincronizar();
    } catch (IOException e) {
      throw new ErroDeLog("falha no fsync de " + diretorio, e);
    }
  }

  @Override
  public void close() {
    try {
      for (Segmento segmento : segmentos) {
        segmento.close();
      }
    } catch (IOException e) {
      throw new ErroDeLog("falha ao fechar log em " + diretorio, e);
    }
  }

  Segmento segmentoQueContem(long offset) {
    List<Segmento> retrato = segmentos;
    int inferior = 0;
    int superior = retrato.size() - 1;
    int escolhido = 0;
    while (inferior <= superior) {
      int meio = (inferior + superior) >>> 1;
      if (retrato.get(meio).offsetBase() <= offset) {
        escolhido = meio;
        inferior = meio + 1;
      } else {
        superior = meio - 1;
      }
    }
    return retrato.get(escolhido);
  }

  Optional<Segmento> segmentoApos(Segmento segmento) {
    List<Segmento> retrato = segmentos;
    int posicao = retrato.indexOf(segmento) + 1;
    return posicao < retrato.size() ? Optional.of(retrato.get(posicao)) : Optional.empty();
  }

  private long primeiroOffsetDoLog() {
    return segmentos.getFirst().offsetBase();
  }

  private void abrirSegmentos(List<Long> offsetsBase) throws IOException {
    for (int i = 0; i < offsetsBase.size(); i++) {
      boolean ultimo = i == offsetsBase.size() - 1;
      Segmento segmento = Segmento.abrir(diretorio, offsetsBase.get(i), configuracao, ultimo);
      segmentos.add(segmento);
      ultimoTimestamp = Math.max(ultimoTimestamp, segmento.ultimoTimestamp());
    }
    ativo = segmentos.getLast();
  }

  private void rolar() throws IOException {
    Segmento novo = Segmento.abrir(diretorio, ativo.proximoOffset(), configuracao, true);
    segmentos.add(novo);
    ativo = novo;
  }

  /** Arquivos com nome fora da convencao sao ignorados: o diretorio pode ser de outra pessoa. */
  private static List<Long> offsetsBaseExistentes(Path diretorio) throws IOException {
    List<Long> offsetsBase = new ArrayList<>();
    try (Stream<Path> arquivos = Files.list(diretorio)) {
      for (Path arquivo : arquivos.toList()) {
        offsetBaseDoNome(arquivo.getFileName().toString()).ifPresent(offsetsBase::add);
      }
    }
    Collections.sort(offsetsBase);
    if (offsetsBase.isEmpty()) {
      offsetsBase.add(0L);
    }
    return offsetsBase;
  }

  private static Optional<Long> offsetBaseDoNome(String nome) {
    if (!nome.endsWith(Segmento.SUFIXO_SEGMENTO)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          Long.parseLong(nome.substring(0, nome.length() - Segmento.SUFIXO_SEGMENTO.length())));
    } catch (NumberFormatException naoEhSegmentoNosso) {
      return Optional.empty();
    }
  }
}
