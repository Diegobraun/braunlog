package com.braunlog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import com.braunlog.formato.EntradaDeIndice;
import com.braunlog.formato.FormatoRegistro;
import com.braunlog.formato.IndiceEsparso;
import com.braunlog.formato.ResultadoDecodificacao;
import com.braunlog.formato.Varredor;

/**
 * Um arquivo de segmento e o indice esparso que o acompanha.
 *
 * <p>O canal e compartilhado por todos os leitores: {@code read(buffer, posicao)} nao mexe na
 * posicao do canal, entao leitura concorrente dispensa lock. O que protege o leitor de ver um
 * registro pela metade e {@code limiteLegivel}, que so avanca depois de o registro inteiro ter sido
 * escrito.
 */
final class Segmento implements AutoCloseable {

  static final String SUFIXO_SEGMENTO = ".log";
  static final String SUFIXO_INDICE = ".indice";
  private static final int DIGITOS_DO_NOME = 20;

  private final Path arquivo;
  private final long offsetBase;
  private final FileChannel canal;
  private final IndiceEsparso indice;
  private final Varredor varredor;

  private volatile long limiteLegivel;
  private int proximoOffsetRelativo;
  private long ultimoTimestamp;

  private Segmento(Path arquivo, long offsetBase, FileChannel canal, IndiceEsparso indice,
      int tamanhoMaximoRegistro) {
    this.arquivo = arquivo;
    this.offsetBase = offsetBase;
    this.canal = canal;
    this.indice = indice;
    this.varredor = new Varredor(canal, tamanhoMaximoRegistro);
  }

  /**
   * @param ultimoDoLog quando verdadeiro, uma cauda parcial e truncada; nos demais segmentos ela e
   *     corrupcao, porque so o ultimo segmento pode ter sido interrompido por uma queda
   */
  static Segmento abrir(
      Path diretorio, long offsetBase, ConfiguracaoLog configuracao, boolean ultimoDoLog)
      throws IOException {
    Path arquivo = diretorio.resolve(nomeDeArquivo(offsetBase));
    FileChannel canal =
        FileChannel.open(
            arquivo, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    IndiceEsparso indice =
        IndiceEsparso.abrir(
            arquivoDeIndice(arquivo), configuracao.intervaloDoIndiceEmBytes(), canal.size());
    Segmento segmento =
        new Segmento(
            arquivo, offsetBase, canal, indice, configuracao.tamanhoMaximoRegistro());
    segmento.recuperar(ultimoDoLog);
    return segmento;
  }

  static String nomeDeArquivo(long offsetBase) {
    return ("%0" + DIGITOS_DO_NOME + "d" + SUFIXO_SEGMENTO).formatted(offsetBase);
  }

  static Path arquivoDeIndice(Path arquivoDeSegmento) {
    String nome = arquivoDeSegmento.getFileName().toString();
    return arquivoDeSegmento.resolveSibling(
        nome.substring(0, nome.length() - SUFIXO_SEGMENTO.length()) + SUFIXO_INDICE);
  }

  long offsetBase() {
    return offsetBase;
  }

  long proximoOffset() {
    return offsetBase + proximoOffsetRelativo;
  }

  long bytes() {
    return limiteLegivel;
  }

  Path arquivo() {
    return arquivo;
  }

  int entradasNoIndice() {
    return indice.quantidadeDeEntradas();
  }

  Offset anexar(Registro registro, long timestamp) throws IOException {
    int tamanho = FormatoRegistro.tamanhoCodificado(registro);
    int posicao = (int) limiteLegivel;
    int offsetRelativo = proximoOffsetRelativo;

    ByteBuffer buffer = ByteBuffer.allocate(tamanho);
    FormatoRegistro.codificar(buffer, registro, offsetRelativo, timestamp);
    buffer.flip();
    escreverTudo(buffer, posicao);

    proximoOffsetRelativo = offsetRelativo + 1;
    ultimoTimestamp = timestamp;
    limiteLegivel = (long) posicao + tamanho;
    indice.anotarSeAlcancouOIntervalo(offsetRelativo, posicao, timestamp);
    return Offset.de(offsetBase + offsetRelativo);
  }

  long ultimoTimestamp() {
    return ultimoTimestamp;
  }

  ResultadoDecodificacao ler(long posicao) throws IOException {
    return varredor.ler(posicao, limiteLegivel, offsetBase);
  }

  /**
   * Primeiro registro deste segmento com timestamp maior ou igual ao alvo. O indice da o ponto de
   * partida; a resposta exata sai da varredura, porque o indice e esparso.
   */
  Optional<Offset> primeiroOffsetComTimestamp(long alvo) throws IOException {
    long posicao = posicaoParaTimestamp(alvo);
    while (true) {
      switch (ler(posicao)) {
        case ResultadoDecodificacao.Sucesso sucesso -> {
          if (sucesso.registro().timestamp() >= alvo) {
            return Optional.of(sucesso.registro().offset());
          }
          posicao += sucesso.bytesConsumidos();
        }
        case ResultadoDecodificacao.Corrompido corrompido ->
            throw new ErroDeCorrupcao(
                "segmento " + arquivo + " corrompido na posicao " + posicao + ": "
                    + corrompido.motivo());
        default -> {
          return Optional.empty();
        }
      }
    }
  }

  long posicaoParaOffset(long offset) {
    long relativo = offset - offsetBase;
    if (relativo > Integer.MAX_VALUE) {
      return limiteLegivel;
    }
    return indice.pisoPorOffset((int) Math.max(0, relativo)).posicao();
  }

  long posicaoParaTimestamp(long timestamp) {
    return indice.pisoPorTimestamp(timestamp).posicao();
  }

  void sincronizar() throws IOException {
    canal.force(true);
  }

  @Override
  public void close() throws IOException {
    try {
      indice.close();
    } finally {
      canal.close();
    }
  }

  private void escreverTudo(ByteBuffer buffer, long posicao) throws IOException {
    while (buffer.hasRemaining()) {
      canal.write(buffer, posicao + buffer.position());
    }
  }

  /**
   * Varre a partir da ultima entrada do indice — nao do inicio do arquivo — para achar o fim do
   * segmento. Cada registro visto realimenta o indice, entao um indice ausente e reconstruido e um
   * indice parcial e completado pelo mesmo codigo.
   */
  private void recuperar(boolean ultimoDoLog) throws IOException {
    long tamanhoArquivo = canal.size();
    EntradaDeIndice partida = indice.pisoPorOffset(Integer.MAX_VALUE);
    long posicao = partida.posicao();
    int offsetRelativo = partida.offsetRelativo();

    boolean varrendo = true;
    while (varrendo) {
      switch (varredor.ler(posicao, tamanhoArquivo, offsetBase)) {
        case ResultadoDecodificacao.Sucesso sucesso -> {
          RegistroLido lido = sucesso.registro();
          offsetRelativo = (int) (lido.offset().valor() - offsetBase);
          indice.anotarSeAlcancouOIntervalo(offsetRelativo, (int) posicao, lido.timestamp());
          offsetRelativo++;
          ultimoTimestamp = lido.timestamp();
          posicao += sucesso.bytesConsumidos();
        }
        case ResultadoDecodificacao.Corrompido corrompido ->
            throw new ErroDeCorrupcao(
                "segmento " + arquivo + " corrompido na posicao " + posicao + ": "
                    + corrompido.motivo());
        case ResultadoDecodificacao.Parcial parcial -> {
          if (!ultimoDoLog) {
            throw new ErroDeCorrupcao(
                "segmento fechado " + arquivo + " termina com registro incompleto na posicao "
                    + posicao);
          }
          varrendo = false;
        }
        case ResultadoDecodificacao.Fim ignorado -> varrendo = false;
      }
    }

    if (posicao < tamanhoArquivo) {
      canal.truncate(posicao);
    }
    limiteLegivel = posicao;
    proximoOffsetRelativo = offsetRelativo;
  }
}
