package com.braunlog;

import static com.braunlog.Suporte.configuracaoPadrao;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

/**
 * O indice esparso e uma dica: devolver sempre a posicao zero nao produziria resposta errada, so
 * leitura mais lenta. Por isso a dica precisa de teste proprio — nenhum teste de leitura falharia se
 * ela parasse de funcionar.
 */
class SegmentoTest {

  private static final long INICIO = 1_700_000_000_000L;
  private static final int INTERVALO_CURTO = 64;
  private static final int REGISTROS = 60;

  @TempDir Path diretorio;

  private final RelogioAjustavel relogio = RelogioAjustavel.em(INICIO);

  @Test
  void aDicaPorOffsetDeveApontarParaUmRegistroQueNaoPassaDoProcurado() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, REGISTROS);
      Segmento segmento = log.segmentoQueContem(0);

      // when
      long posicaoDoUltimo = segmento.posicaoParaOffset(REGISTROS - 1);
      long posicaoDoMeio = segmento.posicaoParaOffset(REGISTROS / 2);

      // then
      assertThat(segmento.posicaoParaOffset(0)).isZero();
      assertThat(posicaoDoMeio).isPositive();
      assertThat(posicaoDoUltimo).isGreaterThanOrEqualTo(posicaoDoMeio);
      assertThat(posicaoDoUltimo).isLessThan(segmento.bytes());
    }
  }

  @Test
  void aDicaPorTimestampDeveAcompanharACaminhadaDoRelogio() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, REGISTROS);
      Segmento segmento = log.segmentoQueContem(0);

      // when
      long posicaoNoComeco = segmento.posicaoParaTimestamp(INICIO);
      long posicaoNoFim = segmento.posicaoParaTimestamp(INICIO + REGISTROS);

      // then
      assertThat(posicaoNoComeco).isZero();
      assertThat(posicaoNoFim).isPositive().isLessThan(segmento.bytes());
      assertThat(segmento.posicaoParaTimestamp(INICIO - 1)).isZero();
    }
  }

  @Test
  void aDicaPorOffsetNaoDevePassarDoFimQuandoOOffsetPedidoNaoCabeNumInteiro() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, 3);
      Segmento segmento = log.segmentoQueContem(0);

      // when / then
      assertThat(segmento.posicaoParaOffset(Integer.MAX_VALUE + 1L)).isEqualTo(segmento.bytes());
    }
  }

  @Test
  void deveRotearCadaOffsetParaOSegmentoQueRealmenteOContem() {
    // given
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(registro("chave-00", "valor-00"));
    ConfiguracaoLog configuracao =
        configuracaoPadrao()
            .comTamanhoMaximoRegistro(tamanhoDoRegistro)
            .comBytesMaximosPorSegmento(tamanhoDoRegistro * 4L);
    try (Log log = Log.abrir(diretorio, configuracao)) {
      anexar(log, 20);
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(5);

      // when / then
      for (int offset = 0; offset < 20; offset++) {
        assertThat(log.segmentoQueContem(offset).offsetBase())
            .describedAs("offset %d", offset)
            .isEqualTo(offset / 4 * 4);
      }
      assertThat(log.segmentoQueContem(1_000).offsetBase()).isEqualTo(16);
    }
  }

  private ConfiguracaoLog configuracao() {
    return ConfiguracaoLog.padrao(relogio).comIntervaloDoIndiceEmBytes(INTERVALO_CURTO);
  }

  private void anexar(Log log, int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      log.anexar(registro("chave-%02d".formatted(i), "valor-%02d".formatted(i)));
      relogio.avancar(Duration.ofMillis(1));
    }
  }
}
