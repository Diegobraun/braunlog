package com.braunlog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

import com.braunlog.formato.FormatoRegistro;
import com.braunlog.formato.ResultadoDecodificacao;
import com.braunlog.formato.Varredor;

/**
 * Commit log append-only, com um unico escritor e varios leitores.
 *
 * <p>Nesta fase o log e um unico arquivo de segmento comecando no offset zero. Segmentacao entra na
 * fase 2 sem mudar esta API.
 */
public final class Log implements AutoCloseable {

  static final String SUFIXO_SEGMENTO = ".log";
  private static final int DIGITOS_NOME_SEGMENTO = 20;
  private static final long OFFSET_BASE = 0;

  private final Path arquivo;
  private final ConfiguracaoLog configuracao;
  private final FileChannel canal;

  private long proximoOffset;
  private volatile long limiteLegivel;

  private Log(Path arquivo, ConfiguracaoLog configuracao, FileChannel canal) {
    this.arquivo = arquivo;
    this.configuracao = configuracao;
    this.canal = canal;
  }

  public static Log abrir(Path diretorio, ConfiguracaoLog configuracao) {
    Objects.requireNonNull(diretorio, "diretorio");
    Objects.requireNonNull(configuracao, "configuracao");
    try {
      Files.createDirectories(diretorio);
      Path arquivo = diretorio.resolve(nomeDeSegmento(0));
      FileChannel canal =
          FileChannel.open(
              arquivo,
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE);
      Log log = new Log(arquivo, configuracao, canal);
      log.recuperar();
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
          "registro de "
              + tamanho
              + " bytes excede o maximo de "
              + configuracao.tamanhoMaximoRegistro());
    }
    long offset = proximoOffset;
    ByteBuffer buffer = ByteBuffer.allocate(tamanho);
    FormatoRegistro.codificar(buffer, registro, (int) offset, configuracao.relogio().millis());
    buffer.flip();
    escreverTudo(buffer, limiteLegivel);
    proximoOffset = offset + 1;
    limiteLegivel += tamanho;
    return Offset.de(offset);
  }

  public Leitor lerDe(Offset offset) {
    Objects.requireNonNull(offset, "offset");
    return Leitor.abrir(arquivo, configuracao, () -> limiteLegivel, OFFSET_BASE, offset);
  }

  public Optional<Offset> ultimoOffset() {
    long proximo = proximoOffsetVisivel();
    return proximo == OFFSET_BASE ? Optional.empty() : Optional.of(Offset.de(proximo - 1));
  }

  public Offset proximoOffset() {
    return Offset.de(proximoOffsetVisivel());
  }

  public void forcarSincronizacao() {
    try {
      canal.force(true);
    } catch (IOException e) {
      throw new ErroDeLog("falha no fsync de " + arquivo, e);
    }
  }

  @Override
  public void close() {
    try {
      canal.close();
    } catch (IOException e) {
      throw new ErroDeLog("falha ao fechar " + arquivo, e);
    }
  }

  static String nomeDeSegmento(long offsetBase) {
    return ("%0" + DIGITOS_NOME_SEGMENTO + "d" + SUFIXO_SEGMENTO).formatted(offsetBase);
  }

  private synchronized long proximoOffsetVisivel() {
    return proximoOffset;
  }

  private void escreverTudo(ByteBuffer buffer, long posicao) {
    try {
      while (buffer.hasRemaining()) {
        canal.write(buffer, posicao + buffer.position());
      }
    } catch (IOException e) {
      throw new ErroDeLog("falha ao escrever em " + arquivo, e);
    }
  }

  /**
   * Varre o segmento na abertura para descobrir onde continuar escrevendo. Um registro parcial no
   * fim e o rastro esperado de uma queda: o arquivo e truncado ali, porque deixar bytes orfaos
   * depois do proximo append transformaria escrita parcial em corrupcao.
   */
  private void recuperar() throws IOException {
    Varredor varredor = new Varredor(canal, configuracao.tamanhoMaximoRegistro());
    long tamanhoArquivo = canal.size();
    long posicao = 0;
    long offset = OFFSET_BASE;
    boolean varrendo = true;
    while (varrendo) {
      switch (varredor.ler(posicao, tamanhoArquivo, OFFSET_BASE)) {
        case ResultadoDecodificacao.Sucesso sucesso -> {
          posicao += sucesso.bytesConsumidos();
          offset = sucesso.registro().offset().valor() + 1;
        }
        case ResultadoDecodificacao.Corrompido corrompido ->
            throw new ErroDeCorrupcao(
                "segmento " + arquivo + " corrompido na posicao " + posicao + ": "
                    + corrompido.motivo());
        case ResultadoDecodificacao.Parcial ignorado -> varrendo = false;
        case ResultadoDecodificacao.Fim ignorado -> varrendo = false;
      }
    }
    if (posicao < tamanhoArquivo) {
      canal.truncate(posicao);
    }
    proximoOffset = offset;
    limiteLegivel = posicao;
  }
}
