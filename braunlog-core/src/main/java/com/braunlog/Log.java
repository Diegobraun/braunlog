package com.braunlog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
  private long instanteDaUltimaSincronizacao;
  private long sincronizacoes;

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
      sincronizarConformeOModo(timestamp);
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

  public long bytes() {
    return segmentos.stream().mapToLong(Segmento::bytes).sum();
  }

  /**
   * Apaga segmentos fechados enquanto a politica de retencao mandar. Roda sozinha na abertura e a
   * cada rolagem; nao ha thread de background, entao um log parado nunca encolhe sozinho.
   */
  public synchronized void aplicarRetencao() {
    try {
      long agora = Math.max(configuracao.relogio().millis(), ultimoTimestamp);
      while (segmentos.size() > 1) {
        Segmento candidato = segmentos.getFirst();
        if (!configuracao.politicaRetencao()
            .deveDescartar(bytes(), candidato.ultimoTimestamp(), agora)) {
          return;
        }
        segmentos.remove(candidato);
        candidato.apagar();
        sincronizarDiretorioSeNecessario();
      }
    } catch (IOException e) {
      throw new ErroDeLog("falha ao aplicar retencao em " + diretorio, e);
    }
  }

  /**
   * Reescreve os segmentos fechados cujo dirty ratio passou do minimo configurado, mantendo apenas
   * o ultimo registro de cada chave. O segmento ativo nunca e tocado.
   */
  public synchronized RelatorioDeCompactacao compactar() {
    List<Segmento> fechados = List.copyOf(segmentos.subList(0, segmentos.size() - 1));
    if (fechados.isEmpty()) {
      return RelatorioDeCompactacao.NADA_A_FAZER;
    }
    try {
      Map<ByteBuffer, Long> ultimos = Compactador.ultimoOffsetPorChave(fechados);
      int reescritos = 0;
      long bytesAntes = 0;
      long bytesDepois = 0;
      for (Segmento antigo : fechados) {
        if (Compactador.dirtyRatio(antigo, ultimos) < configuracao.dirtyRatioMinimo()) {
          continue;
        }
        bytesAntes += antigo.bytes();
        Segmento novo = Compactador.reescrever(antigo, ultimos, configuracao);
        segmentos.set(segmentos.indexOf(antigo), novo);
        antigo.close();
        bytesDepois += novo.bytes();
        reescritos++;
      }
      sincronizarDiretorioSeNecessario();
      return new RelatorioDeCompactacao(reescritos, bytesAntes, bytesDepois);
    } catch (IOException e) {
      throw new ErroDeLog("falha ao compactar " + diretorio, e);
    }
  }

  public synchronized void forcarSincronizacao() {
    try {
      sincronizar();
    } catch (IOException e) {
      throw new ErroDeLog("falha no fsync de " + diretorio, e);
    }
  }

  /** Quantos {@code fsync} este log ja pediu ao sistema operacional desde que foi aberto. */
  public synchronized long quantidadeDeSincronizacoes() {
    return sincronizacoes;
  }

  /**
   * Fecha o log sincronizando antes, exceto no modo {@link ModoDurabilidade.Nenhum}: quem escolheu
   * esse modo pediu que o braunlog nunca chamasse {@code fsync}, e fechar nao e motivo para
   * desobedecer.
   */
  @Override
  public synchronized void close() {
    try {
      if (!(configuracao.modoDurabilidade() instanceof ModoDurabilidade.Nenhum)) {
        sincronizar();
      }
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

  /**
   * Busca pelo offset base, e nao por identidade: o objeto do segmento pode ter sido trocado por um
   * compactado enquanto um leitor ainda segurava o antigo.
   */
  Optional<Segmento> segmentoApos(Segmento segmento) {
    for (Segmento candidato : segmentos) {
      if (candidato.offsetBase() > segmento.offsetBase()) {
        return Optional.of(candidato);
      }
    }
    return Optional.empty();
  }

  boolean contem(Segmento segmento) {
    return segmentos.contains(segmento);
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
    aplicarRetencao();
  }

  private void rolar() throws IOException {
    Segmento anterior = ativo;
    Segmento novo = Segmento.abrir(diretorio, anterior.proximoOffset(), configuracao, true);
    segmentos.add(novo);
    ativo = novo;
    if (!(configuracao.modoDurabilidade() instanceof ModoDurabilidade.Nenhum)) {
      anterior.sincronizar();
      sincronizarDiretorio();
    }
    aplicarRetencao();
  }

  private void sincronizarDiretorioSeNecessario() {
    if (!(configuracao.modoDurabilidade() instanceof ModoDurabilidade.Nenhum)) {
      sincronizarDiretorio();
    }
  }

  private void sincronizarConformeOModo(long agora) throws IOException {
    switch (configuracao.modoDurabilidade()) {
      case ModoDurabilidade.ACadaAppend ignorado -> sincronizar();
      case ModoDurabilidade.PorIntervalo(Duration intervalo) -> {
        if (agora - instanteDaUltimaSincronizacao >= intervalo.toMillis()) {
          sincronizar();
        }
      }
      case ModoDurabilidade.Nenhum ignorado -> {
        // o contrato deste modo e justamente nao chamar fsync
      }
    }
  }

  private void sincronizar() throws IOException {
    ativo.sincronizar();
    instanteDaUltimaSincronizacao = ultimoTimestamp;
    sincronizacoes++;
  }

  /**
   * Sincronizar o arquivo novo nao basta: a entrada dele no diretorio e outro bloco de metadados, e
   * sem este fsync um segmento recem-criado pode sumir numa queda de energia. Nem todo sistema de
   * arquivos deixa abrir um diretorio como canal, e onde nao deixa a entrada ja e gravada de outra
   * forma — por isso a falha aqui nao derruba o append.
   */
  private void sincronizarDiretorio() {
    try (FileChannel canal = FileChannel.open(diretorio, StandardOpenOption.READ)) {
      canal.force(true);
    } catch (IOException naoSuportadoNestaPlataforma) {
      // ver o javadoc: nao ha o que fazer, e falhar seria pior
    }
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
