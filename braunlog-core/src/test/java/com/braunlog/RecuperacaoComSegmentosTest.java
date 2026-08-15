package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.sequencia;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.configuracaoPadrao;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.braunlog.formato.FormatoRegistro;

/**
 * Recuperacao quando o log tem mais de um segmento. Aqui aparece a assimetria: cauda parcial no
 * ultimo segmento e queda, e se trunca; a mesma cauda num segmento fechado e corrupcao, porque
 * segmento fechado nunca fica pela metade.
 */
class RecuperacaoComSegmentosTest {

  private static final int REGISTROS = 10;
  private static final int REGISTROS_POR_SEGMENTO = 4;
  private static final int REGISTROS_NO_ULTIMO = REGISTROS % REGISTROS_POR_SEGMENTO;
  private static final long OFFSET_BASE_DO_ULTIMO =
      (long) (REGISTROS / REGISTROS_POR_SEGMENTO) * REGISTROS_POR_SEGMENTO;

  @TempDir Path diretorio;

  static IntStream posicoesDeCorteNoUltimoSegmento() {
    return IntStream.rangeClosed(0, REGISTROS_NO_ULTIMO * tamanhoDoRegistro());
  }

  @ParameterizedTest
  @MethodSource("posicoesDeCorteNoUltimoSegmento")
  void deveTruncarCaudaParcialDoUltimoSegmentoEmQualquerPosicaoDeCorte(int posicaoDeCorte)
      throws IOException {
    // given
    escreverLogComVariosSegmentos();
    truncar(arquivoDoUltimoSegmento(), posicaoDeCorte);
    long intactos = OFFSET_BASE_DO_ULTIMO + posicaoDeCorte / tamanhoDoRegistro();

    // when
    try (Log log = Log.abrir(diretorio, configuracao())) {

      // then
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, intactos));
      assertThat(log.anexar(registro("depois", "x"))).isEqualTo(Offset.de(intactos));
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, intactos + 1));
    }
  }

  @Test
  void deveAbrirNormalmenteQuandoAQuedaAconteceuEntreCriarESegmentoEEscreverNele() throws IOException {
    // given
    escreverLogComVariosSegmentos();
    Path segmentoVazio = diretorio.resolve(Segmento.nomeDeArquivo(REGISTROS));
    Files.createFile(segmentoVazio);

    // when
    try (Log log = Log.abrir(diretorio, configuracao())) {

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(4);
      assertThat(log.proximoOffset()).isEqualTo(Offset.de(REGISTROS));
      assertThat(log.anexar(registro("depois", "x"))).isEqualTo(Offset.de(REGISTROS));
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, REGISTROS + 1));
    }
  }

  @Test
  void deveTratarCaudaParcialDeSegmentoFechadoComoCorrupcao() throws IOException {
    // given
    escreverLogComVariosSegmentos();
    Path primeiroSegmento = diretorio.resolve(Segmento.nomeDeArquivo(0));
    truncar(primeiroSegmento, tamanhoDoRegistro() * REGISTROS_POR_SEGMENTO - 3);

    // when / then
    assertThatThrownBy(() -> Log.abrir(diretorio, configuracao()))
        .isInstanceOf(ErroDeCorrupcao.class)
        .hasMessageContaining("segmento fechado")
        .hasMessageContaining(Segmento.nomeDeArquivo(0));
  }

  @Test
  void deveReconstruirOsIndicesDeTodosOsSegmentosDepoisDeApagados() throws IOException {
    // given
    escreverLogComVariosSegmentos();
    apagarIndices();

    // when
    try (Log log = Log.abrir(diretorio, configuracao())) {

      // then
      assertThat(log.segmentoQueContem(0).entradasNoIndice()).isPositive();
      assertThat(log.segmentoQueContem(OFFSET_BASE_DO_ULTIMO).entradasNoIndice()).isPositive();
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, REGISTROS));
    }
  }

  private void escreverLogComVariosSegmentos() {
    try (Log log = Log.abrir(diretorio, configuracao())) {
      for (int i = 0; i < REGISTROS; i++) {
        log.anexar(registroDe(i));
      }
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(3);
    }
  }

  private Path arquivoDoUltimoSegmento() {
    return diretorio.resolve(Segmento.nomeDeArquivo(OFFSET_BASE_DO_ULTIMO));
  }

  private void apagarIndices() throws IOException {
    try (var arquivos = Files.list(diretorio)) {
      for (Path arquivo : arquivos.toList()) {
        if (arquivo.getFileName().toString().endsWith(Segmento.SUFIXO_INDICE)) {
          Files.delete(arquivo);
        }
      }
    }
  }

  private static void truncar(Path arquivo, long tamanho) throws IOException {
    try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.WRITE)) {
      canal.truncate(tamanho);
    }
  }

  private ConfiguracaoLog configuracao() {
    return configuracaoPadrao()
        .comTamanhoMaximoRegistro(tamanhoDoRegistro())
        .comBytesMaximosPorSegmento((long) tamanhoDoRegistro() * REGISTROS_POR_SEGMENTO)
        .comIntervaloDoIndiceEmBytes(tamanhoDoRegistro());
  }

  private static int tamanhoDoRegistro() {
    return FormatoRegistro.tamanhoCodificado(registroDe(0));
  }

  private static Registro registroDe(int indice) {
    return Registro.de(bytes("chave-%02d".formatted(indice)), bytes("valor-%02d".formatted(indice)));
  }
}
