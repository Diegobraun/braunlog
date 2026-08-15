package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.sequencia;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

class DurabilidadeTest {

  private static final long INICIO = 1_700_000_000_000L;
  private static final Duration INTERVALO = Duration.ofMillis(200);

  @TempDir Path diretorio;

  private final RelogioAjustavel relogio = RelogioAjustavel.em(INICIO);

  @Test
  void modoACadaAppendDeveSincronizarUmaVezPorRegistro() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao(ModoDurabilidade.A_CADA_APPEND))) {

      // when
      for (int i = 0; i < 5; i++) {
        log.anexar(registro("chave-" + i, "valor"));
      }

      // then
      assertThat(log.quantidadeDeSincronizacoes()).isEqualTo(5);
    }
  }

  @Test
  void modoNenhumNuncaDeveSincronizarSozinho() {
    // given
    Log log = Log.abrir(diretorio, configuracao(ModoDurabilidade.NENHUM));

    // when
    for (int i = 0; i < 5; i++) {
      log.anexar(registro("chave-" + i, "valor"));
    }
    log.close();

    // then
    assertThat(log.quantidadeDeSincronizacoes()).isZero();
  }

  @Test
  void modoNenhumDeveSincronizarQuandoAlguemPedeExplicitamente() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao(ModoDurabilidade.NENHUM))) {
      log.anexar(registro("a", "1"));

      // when
      log.forcarSincronizacao();

      // then
      assertThat(log.quantidadeDeSincronizacoes()).isEqualTo(1);
    }
  }

  @Test
  void modoPorIntervaloDeveSincronizarSoQuandoOIntervaloPassou() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao(ModoDurabilidade.porIntervalo(INTERVALO)))) {

      // when
      log.anexar(registro("a", "1"));
      long depoisDoPrimeiro = log.quantidadeDeSincronizacoes();
      relogio.avancar(INTERVALO.minusMillis(1));
      log.anexar(registro("b", "2"));
      long antesDeVencer = log.quantidadeDeSincronizacoes();
      relogio.avancar(Duration.ofMillis(1));
      log.anexar(registro("c", "3"));

      // then
      assertThat(depoisDoPrimeiro).isEqualTo(1);
      assertThat(antesDeVencer).isEqualTo(1);
      assertThat(log.quantidadeDeSincronizacoes()).isEqualTo(2);
    }
  }

  @Test
  void modoPorIntervaloNaoDeveSincronizarSozinhoComOLogOcioso() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao(ModoDurabilidade.porIntervalo(INTERVALO)))) {
      log.anexar(registro("a", "1"));

      // when
      relogio.avancar(Duration.ofHours(1));

      // then
      assertThat(log.quantidadeDeSincronizacoes()).isEqualTo(1);
    }
  }

  @Test
  void fecharDeveSincronizarExcetoNoModoNenhum() {
    // given
    Path outroDiretorio = diretorio.resolve("outro");
    Log comFsync = Log.abrir(diretorio, configuracao(ModoDurabilidade.porIntervalo(INTERVALO)));
    Log semFsync = Log.abrir(outroDiretorio, configuracao(ModoDurabilidade.NENHUM));
    comFsync.anexar(registro("a", "1"));
    semFsync.anexar(registro("a", "1"));
    long sincronizacoesAntesDeFechar = comFsync.quantidadeDeSincronizacoes();

    // when
    comFsync.close();
    semFsync.close();

    // then
    assertThat(comFsync.quantidadeDeSincronizacoes()).isEqualTo(sincronizacoesAntesDeFechar + 1);
    assertThat(semFsync.quantidadeDeSincronizacoes()).isZero();
  }

  @Test
  void deveSincronizarOSegmentoQueAcabouDeFecharAoRolar() {
    // given
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(registro("chave-00", "valor-00"));
    ConfiguracaoLog configuracao =
        configuracao(ModoDurabilidade.porIntervalo(Duration.ofHours(1)))
            .comTamanhoMaximoRegistro(tamanhoDoRegistro)
            .comBytesMaximosPorSegmento(tamanhoDoRegistro * 2L);

    try (Log log = Log.abrir(diretorio, configuracao)) {
      // when
      for (int i = 0; i < 5; i++) {
        log.anexar(registro("chave-%02d".formatted(i), "valor-%02d".formatted(i)));
      }

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(3);
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, 5));
    }
  }

  @Test
  void deveRecusarIntervaloNaoPositivo() {
    assertThatThrownBy(() -> ModoDurabilidade.porIntervalo(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positivo");
    assertThatThrownBy(() -> ModoDurabilidade.porIntervalo(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModoDurabilidade.PorIntervalo(null))
        .isInstanceOf(NullPointerException.class);
  }

  private ConfiguracaoLog configuracao(ModoDurabilidade modo) {
    return ConfiguracaoLog.padrao(relogio).comModoDurabilidade(modo);
  }
}
