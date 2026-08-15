package com.braunlog.formato;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToIntFunction;

/**
 * Indice esparso de um segmento: uma entrada a cada intervalo de bytes, nunca uma por registro.
 *
 * <p>O indice e uma dica, nunca a fonte de verdade. Toda busca devolve um ponto de partida seguro —
 * a ultima entrada que comprovadamente nao passou do alvo — e quem chama varre para a frente a
 * partir dali. Um indice vazio, curto ou truncado continua correto; so custa uma varredura maior.
 *
 * <p>Invariante de concurrency: a lista de entradas e copy-on-write, entao um leitor faz busca
 * binaria sobre um retrato imutavel enquanto o writer acrescenta entradas.
 */
public final class IndiceEsparso implements AutoCloseable {

  public static final int BYTES_POR_ENTRADA = 16;

  private static final int DESLOCAMENTO_OFFSET_RELATIVO = 0;
  private static final int DESLOCAMENTO_POSICAO = 4;
  private static final int DESLOCAMENTO_TIMESTAMP = 8;

  private final FileChannel canal;
  private final int intervaloEmBytes;
  private final CopyOnWriteArrayList<EntradaDeIndice> entradas = new CopyOnWriteArrayList<>();

  private IndiceEsparso(FileChannel canal, int intervaloEmBytes) {
    this.canal = canal;
    this.intervaloEmBytes = intervaloEmBytes;
  }

  public static IndiceEsparso abrir(Path arquivo, int intervaloEmBytes, long bytesDoSegmento)
      throws IOException {
    FileChannel canal =
        FileChannel.open(
            arquivo, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    IndiceEsparso indice = new IndiceEsparso(canal, intervaloEmBytes);
    indice.carregar(bytesDoSegmento);
    return indice;
  }

  public boolean vazio() {
    return entradas.isEmpty();
  }

  public int quantidadeDeEntradas() {
    return entradas.size();
  }

  /** Grava uma entrada se o segmento cresceu o intervalo configurado desde a ultima. */
  public void anotarSeAlcancouOIntervalo(int offsetRelativo, int posicao, long timestamp)
      throws IOException {
    if (!entradas.isEmpty() && posicao - ultima().posicao() < intervaloEmBytes) {
      return;
    }
    EntradaDeIndice entrada = new EntradaDeIndice(offsetRelativo, posicao, timestamp);
    escrever(entrada, (long) entradas.size() * BYTES_POR_ENTRADA);
    entradas.add(entrada);
  }

  /** Ponto de partida para achar {@code offsetRelativo}: nunca passa do registro procurado. */
  public EntradaDeIndice pisoPorOffset(int offsetRelativo) {
    return piso(entrada -> Integer.compare(entrada.offsetRelativo(), offsetRelativo));
  }

  /** Ponto de partida para achar o primeiro registro com timestamp maior ou igual ao pedido. */
  public EntradaDeIndice pisoPorTimestamp(long timestamp) {
    return piso(entrada -> Long.compare(entrada.timestamp(), timestamp));
  }

  @Override
  public void close() throws IOException {
    canal.close();
  }

  private EntradaDeIndice piso(ToIntFunction<EntradaDeIndice> comparacaoComOAlvo) {
    List<EntradaDeIndice> retrato = entradas;
    int inferior = 0;
    int superior = retrato.size() - 1;
    EntradaDeIndice piso = EntradaDeIndice.INICIO;
    while (inferior <= superior) {
      int meio = (inferior + superior) >>> 1;
      EntradaDeIndice candidata = retrato.get(meio);
      if (comparacaoComOAlvo.applyAsInt(candidata) <= 0) {
        piso = candidata;
        inferior = meio + 1;
      } else {
        superior = meio - 1;
      }
    }
    return piso;
  }

  private EntradaDeIndice ultima() {
    return entradas.get(entradas.size() - 1);
  }

  private void escrever(EntradaDeIndice entrada, long posicaoNoArquivo) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(BYTES_POR_ENTRADA);
    buffer.putInt(DESLOCAMENTO_OFFSET_RELATIVO, entrada.offsetRelativo());
    buffer.putInt(DESLOCAMENTO_POSICAO, entrada.posicao());
    buffer.putLong(DESLOCAMENTO_TIMESTAMP, entrada.timestamp());
    while (buffer.hasRemaining()) {
      canal.write(buffer, posicaoNoArquivo + buffer.position());
    }
  }

  /**
   * Le o indice inteiro validando cada entrada contra a anterior e contra o tamanho do segmento. Na
   * primeira entrada incoerente o arquivo e truncado: entrada perdida custa varredura, nunca dado
   * errado.
   */
  private void carregar(long bytesDoSegmento) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(canal.size(), tetoDeBytes(bytesDoSegmento)));
    while (buffer.hasRemaining() && canal.read(buffer, buffer.position()) > 0) {
      continue;
    }
    buffer.flip();

    EntradaDeIndice anterior = null;
    while (buffer.remaining() >= BYTES_POR_ENTRADA) {
      int inicio = buffer.position();
      EntradaDeIndice entrada =
          new EntradaDeIndice(
              buffer.getInt(inicio + DESLOCAMENTO_OFFSET_RELATIVO),
              buffer.getInt(inicio + DESLOCAMENTO_POSICAO),
              buffer.getLong(inicio + DESLOCAMENTO_TIMESTAMP));
      if (!coerente(entrada, anterior, bytesDoSegmento)) {
        break;
      }
      entradas.add(entrada);
      anterior = entrada;
      buffer.position(inicio + BYTES_POR_ENTRADA);
    }
    canal.truncate((long) entradas.size() * BYTES_POR_ENTRADA);
  }

  /** Nenhum indice honesto tem mais entradas do que o segmento comporta intervalos. */
  private long tetoDeBytes(long bytesDoSegmento) {
    return (bytesDoSegmento / intervaloEmBytes + 1) * BYTES_POR_ENTRADA;
  }

  private static boolean coerente(
      EntradaDeIndice entrada, EntradaDeIndice anterior, long bytesDoSegmento) {
    if (entrada.offsetRelativo() < 0 || entrada.posicao() < 0) {
      return false;
    }
    if (entrada.posicao() >= bytesDoSegmento) {
      return false;
    }
    if (anterior == null) {
      return true;
    }
    return entrada.offsetRelativo() > anterior.offsetRelativo()
        && entrada.posicao() > anterior.posicao()
        && entrada.timestamp() >= anterior.timestamp();
  }
}
