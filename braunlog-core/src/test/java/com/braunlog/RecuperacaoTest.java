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
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RecuperacaoTest {

  @TempDir Path diretorio;

  static IntStream posicoesDeCorte() {
    return IntStream.rangeClosed(0, AmostraDeLog.tamanhoTotal());
  }

  static IntStream posicoesDeByte() {
    return IntStream.range(0, AmostraDeLog.tamanhoTotal());
  }

  @ParameterizedTest
  @MethodSource("posicoesDeCorte")
  void deveRecuperarTruncandoRegistroParcialEmQualquerPosicaoDeCorte(int posicaoDeCorte) {
    // given
    escreverSegmento(Arrays.copyOf(AmostraDeLog.bytes(), posicaoDeCorte));
    int intactos = AmostraDeLog.registrosCompletosAte(posicaoDeCorte);

    // when
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      List<RegistroLido> lidos = todos(log, Offset.ZERO);

      // then
      assertThat(lidos).isEqualTo(AmostraDeLog.esperados().subList(0, intactos));
      assertThat(offsets(lidos)).isEqualTo(sequencia(0, intactos));
      assertThat(log.anexar(registro("depois", "x"))).isEqualTo(Offset.de(intactos));
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, intactos + 1));
    }
  }

  @ParameterizedTest
  @MethodSource("posicoesDeCorte")
  void deveDescartarOsBytesParciaisDoDiscoEmVezDeDeixarLixoDepoisDoProximoAppend(
      int posicaoDeCorte) {
    // given
    escreverSegmento(Arrays.copyOf(AmostraDeLog.bytes(), posicaoDeCorte));
    int intactos = AmostraDeLog.registrosCompletosAte(posicaoDeCorte);

    // when
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("depois", "x"));
    }

    // then
    try (Log reaberto = Log.abrir(diretorio, configuracaoPadrao())) {
      assertThat(offsets(todos(reaberto, Offset.ZERO))).isEqualTo(sequencia(0, intactos + 1));
    }
  }

  @ParameterizedTest
  @MethodSource("posicoesDeCorte")
  void deveTruncarOArquivoNaFronteiraDoUltimoRegistroCompleto(int posicaoDeCorte) throws IOException {
    // given
    escreverSegmento(Arrays.copyOf(AmostraDeLog.bytes(), posicaoDeCorte));
    int intactos = AmostraDeLog.registrosCompletosAte(posicaoDeCorte);

    // when
    Log.abrir(diretorio, configuracaoPadrao()).close();

    // then
    assertThat(Files.size(diretorio.resolve(Log.nomeDeSegmento(0))))
        .isEqualTo(AmostraDeLog.tamanhoDosPrimeiros(intactos));
  }

  @ParameterizedTest
  @MethodSource("posicoesDeByte")
  void deveDetectarCorrupcaoDeQualquerByteSemNuncaDevolverRegistroDiferenteDoEscrito(int posicao) {
    // given
    byte[] conteudo = AmostraDeLog.bytes();
    conteudo[posicao] = (byte) ~conteudo[posicao];
    escreverSegmento(conteudo);

    // when
    List<RegistroLido> lidos = lerOQueForPossivel();

    // then
    assertThat(lidos).isEqualTo(AmostraDeLog.esperados().subList(0, lidos.size()));
    assertThat(lidos).hasSizeLessThan(AmostraDeLog.esperados().size());
  }

  private List<RegistroLido> lerOQueForPossivel() {
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      return todos(log, Offset.ZERO);
    } catch (ErroDeCorrupcao esperado) {
      return List.of();
    }
  }

  private void escreverSegmento(byte[] conteudo) {
    try {
      Files.write(diretorio.resolve(Log.nomeDeSegmento(0)), conteudo);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
