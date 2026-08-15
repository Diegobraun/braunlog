package com.braunlog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.braunlog.formato.ResultadoDecodificacao;

/**
 * Reescreve segmentos fechados mantendo apenas o ultimo registro de cada chave.
 *
 * <p>Duas escolhas moldam tudo aqui: o offset original de cada registro preservado e mantido, entao
 * o segmento compactado fica com lacunas; e o tombstone e preservado, porque ele e o ultimo valor
 * daquela chave e apaga-lo esconderia a delecao de quem ainda nao leu ate ali.
 *
 * <p>A chave e comparada por conteudo usando {@link ByteBuffer#wrap}, que ja tem {@code equals} e
 * {@code hashCode} sobre os bytes. So as chaves e os offsets ficam em memoria; nenhum valor.
 */
final class Compactador {

  private static final String SUFIXO_TEMPORARIO = ".compactando";

  private Compactador() {}

  /** Ultimo offset em que cada chave aparece, considerando todos os segmentos informados. */
  static Map<ByteBuffer, Long> ultimoOffsetPorChave(List<Segmento> segmentos) throws IOException {
    Map<ByteBuffer, Long> ultimos = new HashMap<>();
    for (Segmento segmento : segmentos) {
      percorrer(
          segmento,
          (lido, bytesOcupados) -> {
            if (lido.temChave()) {
              ultimos.put(ByteBuffer.wrap(lido.chave()), lido.offset().valor());
            }
          });
    }
    return ultimos;
  }

  /** Fracao dos bytes do segmento ocupada por registros que ja foram substituidos. */
  static double dirtyRatio(Segmento segmento, Map<ByteBuffer, Long> ultimos) throws IOException {
    long bytes = segmento.bytes();
    if (bytes == 0) {
      return 0;
    }
    long[] sujos = {0};
    percorrer(
        segmento,
        (lido, bytesOcupados) -> {
          if (!sobrevive(lido, ultimos)) {
            sujos[0] += bytesOcupados;
          }
        });
    return (double) sujos[0] / bytes;
  }

  /**
   * Escreve o segmento compactado num arquivo temporario e troca pelo original.
   *
   * <p>A ordem importa: o indice antigo e apagado <em>antes</em> da troca do segmento. Se a queda
   * acontecer no meio, sobra um segmento novo sem indice — que e reconstruido na abertura. A ordem
   * inversa deixaria um indice apontando para posicoes que nao sao mais inicio de registro.
   */
  static Segmento reescrever(
      Segmento antigo, Map<ByteBuffer, Long> ultimos, ConfiguracaoLog configuracao)
      throws IOException {
    Path arquivo = antigo.arquivo();
    Path temporario = arquivo.resolveSibling(nomeTemporario(arquivo));
    Files.deleteIfExists(temporario);
    Files.deleteIfExists(Segmento.arquivoDeIndice(temporario));

    long offsetBase = antigo.offsetBase();
    try (Segmento novo = Segmento.abrirEm(temporario, offsetBase, configuracao, true)) {
      percorrer(
          antigo,
          (lido, bytesOcupados) -> {
            if (sobrevive(lido, ultimos)) {
              novo.anexarComOffset(
                  lido.registro(), (int) (lido.offset().valor() - offsetBase), lido.timestamp());
            }
          });
      novo.sincronizar();
    }

    antigo.apagarIndice();
    Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
    Files.move(
        Segmento.arquivoDeIndice(temporario),
        Segmento.arquivoDeIndice(arquivo),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
    return Segmento.abrir(arquivo.getParent(), offsetBase, configuracao, false);
  }

  private static boolean sobrevive(RegistroLido lido, Map<ByteBuffer, Long> ultimos) {
    if (!lido.temChave()) {
      return true;
    }
    Long ultimo = ultimos.get(ByteBuffer.wrap(lido.chave()));
    return ultimo == null || ultimo == lido.offset().valor();
  }

  private static String nomeTemporario(Path arquivo) {
    String nome = arquivo.getFileName().toString();
    return nome.substring(0, nome.length() - Segmento.SUFIXO_SEGMENTO.length())
        + SUFIXO_TEMPORARIO
        + Segmento.SUFIXO_SEGMENTO;
  }

  private static void percorrer(Segmento segmento, VisitaDeRegistro visita) throws IOException {
    long posicao = 0;
    while (true) {
      switch (segmento.ler(posicao)) {
        case ResultadoDecodificacao.Sucesso sucesso -> {
          visita.visitar(sucesso.registro(), sucesso.bytesConsumidos());
          posicao += sucesso.bytesConsumidos();
        }
        case ResultadoDecodificacao.Corrompido corrompido ->
            throw new ErroDeCorrupcao(
                "segmento " + segmento.arquivo() + " corrompido na posicao " + posicao + ": "
                    + corrompido.motivo());
        default -> {
          return;
        }
      }
    }
  }

  @FunctionalInterface
  private interface VisitaDeRegistro {
    void visitar(RegistroLido registro, int bytesOcupados) throws IOException;
  }
}
