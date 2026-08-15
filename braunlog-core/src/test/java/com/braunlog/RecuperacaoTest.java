package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.sequencia;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.configuracaoPadrao;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
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
    assertThat(Files.size(diretorio.resolve(Segmento.nomeDeArquivo(0))))
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

  /**
   * A varredura de abertura comeca na ultima entrada do indice, entao corrupcao antes dela nao e
   * vista ali. A garantia continua valendo, so muda o momento: quem detecta e a leitura do registro.
   */
  @Test
  void deveDetectarNaLeituraACorrupcaoQueAAberturaNaoVarreu() throws IOException {
    // given
    ConfiguracaoLog configuracao = configuracaoPadrao().comIntervaloDoIndiceEmBytes(64);
    try (Log log = Log.abrir(diretorio, configuracao)) {
      for (int i = 0; i < 40; i++) {
        log.anexar(registro("chave-%02d".formatted(i), "valor-%02d".formatted(i)));
      }
      assertThat(log.segmentoQueContem(0).entradasNoIndice()).isGreaterThan(1);
    }
    corromperByteDoPrimeiroRegistro();

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracao)) {

      // then
      assertThatThrownBy(() -> todos(reaberto, Offset.ZERO))
          .isInstanceOf(ErroDeCorrupcao.class)
          .hasMessageContaining(Segmento.nomeDeArquivo(0));
    }
  }

  private void corromperByteDoPrimeiroRegistro() throws IOException {
    Path arquivo = diretorio.resolve(Segmento.nomeDeArquivo(0));
    byte[] conteudo = Files.readAllBytes(arquivo);
    int byteDoTimestamp = 14;
    conteudo[byteDoTimestamp] = (byte) ~conteudo[byteDoTimestamp];
    Files.write(arquivo, conteudo);
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
      Files.write(diretorio.resolve(Segmento.nomeDeArquivo(0)), conteudo);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
