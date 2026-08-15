package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.sequencia;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.configuracaoPadrao;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndiceDoLogTest {

  private static final int INTERVALO_CURTO = 64;
  private static final int REGISTROS = 40;

  @TempDir Path diretorio;

  @Test
  void deveGravarEntradasDeIndiceConformeOSegmentoCresce() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {

      // when
      anexar(log, REGISTROS);

      // then
      assertThat(log.segmentoQueContem(0).entradasNoIndice()).isGreaterThan(1);
      assertThat(arquivoDeIndice()).exists();
    }
  }

  @Test
  void oIndiceNaoDeveMudarOQueALeituraDevolve() {
    // given
    List<RegistroLido> comIndice;
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, REGISTROS);
      comIndice = todos(log, Offset.ZERO);
    }

    // when
    apagarIndices();

    // then
    try (Log semIndice = Log.abrir(diretorio, configuracao())) {
      assertThat(todos(semIndice, Offset.ZERO)).isEqualTo(comIndice);
      for (int inicio = 0; inicio <= REGISTROS; inicio++) {
        assertThat(offsets(todos(semIndice, Offset.de(inicio))))
            .isEqualTo(sequencia(inicio, REGISTROS));
      }
    }
  }

  @Test
  void deveReconstruirOIndiceApagadoNaAberturaSeguinte() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, REGISTROS);
    }
    apagarIndices();

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracao())) {

      // then
      assertThat(reaberto.segmentoQueContem(0).entradasNoIndice()).isGreaterThan(1);
      assertThat(reaberto.proximoOffset()).isEqualTo(Offset.de(REGISTROS));
      assertThat(reaberto.anexar(registro("depois", "x"))).isEqualTo(Offset.de(REGISTROS));
    }
  }

  @Test
  void deveContinuarOIndiceDeOndeParouAoReabrirSemVarrerOSegmentoInteiro() {
    // given
    int entradasAntes;
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, REGISTROS);
      entradasAntes = log.segmentoQueContem(0).entradasNoIndice();
    }

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracao())) {
      anexar(reaberto, REGISTROS);

      // then
      assertThat(reaberto.segmentoQueContem(0).entradasNoIndice()).isGreaterThan(entradasAntes);
      assertThat(offsets(todos(reaberto, Offset.ZERO))).isEqualTo(sequencia(0, 2L * REGISTROS));
    }
  }

  private ConfiguracaoLog configuracao() {
    return configuracaoPadrao().comIntervaloDoIndiceEmBytes(INTERVALO_CURTO);
  }

  private Path arquivoDeIndice() {
    return Segmento.arquivoDeIndice(diretorio.resolve(Segmento.nomeDeArquivo(0)));
  }

  private void apagarIndices() {
    try (var arquivos = Files.list(diretorio)) {
      for (Path arquivo : arquivos.toList()) {
        if (arquivo.getFileName().toString().endsWith(Segmento.SUFIXO_INDICE)) {
          Files.delete(arquivo);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void anexar(Log log, int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      log.anexar(registro("chave-%03d".formatted(i), "valor-%03d".formatted(i)));
    }
  }
}
