package com.braunlog.formato;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndiceEsparsoTest {

  private static final int INTERVALO = 100;
  private static final long BYTES_DO_SEGMENTO = 10_000;

  @TempDir Path diretorio;

  private Path arquivo;

  @BeforeEach
  void definirArquivo() {
    arquivo = diretorio.resolve("00000000000000000000.indice");
  }

  @Test
  void indiceVazioDeveDevolverOInicioDoSegmentoComoPontoDePartida() throws IOException {
    try (IndiceEsparso indice = abrir()) {
      assertThat(indice.quantidadeDeEntradas()).isZero();
      assertThat(indice.pisoPorOffset(500)).isEqualTo(EntradaDeIndice.INICIO);
      assertThat(indice.pisoPorTimestamp(500).posicao()).isZero();
    }
  }

  @Test
  void deveGravarUmaEntradaACadaIntervaloDeBytes() throws IOException {
    // given
    try (IndiceEsparso indice = abrir()) {

      // when
      indice.anotarSeAlcancouOIntervalo(0, 0, 10);
      indice.anotarSeAlcancouOIntervalo(1, INTERVALO - 1, 11);
      indice.anotarSeAlcancouOIntervalo(2, INTERVALO, 12);
      indice.anotarSeAlcancouOIntervalo(3, INTERVALO + 1, 13);
      indice.anotarSeAlcancouOIntervalo(4, 2 * INTERVALO, 14);

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(3);
      assertThat(Files.size(arquivo)).isEqualTo(3L * IndiceEsparso.BYTES_POR_ENTRADA);
    }
  }

  @Test
  void deveDevolverAUltimaEntradaQueNaoPassaDoAlvo() throws IOException {
    // given
    try (IndiceEsparso indice = comEntradas(3)) {

      // then
      assertThat(indice.pisoPorOffset(0)).isEqualTo(new EntradaDeIndice(0, 0, 1000));
      assertThat(indice.pisoPorOffset(9)).isEqualTo(new EntradaDeIndice(0, 0, 1000));
      assertThat(indice.pisoPorOffset(10)).isEqualTo(new EntradaDeIndice(10, INTERVALO, 1010));
      assertThat(indice.pisoPorOffset(25)).isEqualTo(new EntradaDeIndice(20, 2 * INTERVALO, 1020));
      assertThat(indice.pisoPorOffset(1_000))
          .isEqualTo(new EntradaDeIndice(20, 2 * INTERVALO, 1020));

      assertThat(indice.pisoPorTimestamp(999)).isEqualTo(EntradaDeIndice.INICIO);
      assertThat(indice.pisoPorTimestamp(1009)).isEqualTo(new EntradaDeIndice(0, 0, 1000));
      assertThat(indice.pisoPorTimestamp(1010)).isEqualTo(new EntradaDeIndice(10, INTERVALO, 1010));
      assertThat(indice.pisoPorTimestamp(9999))
          .isEqualTo(new EntradaDeIndice(20, 2 * INTERVALO, 1020));
    }
  }

  @Test
  void deveRecarregarAsEntradasGravadasNaAberturaSeguinte() throws IOException {
    // given
    comEntradas(4).close();

    // when
    try (IndiceEsparso indice = abrir()) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(4);
      assertThat(indice.pisoPorOffset(35)).isEqualTo(new EntradaDeIndice(30, 3 * INTERVALO, 1030));
    }
  }

  @Test
  void deveDescartarEntradaQueApontaAlemDoFimDoSegmento() throws IOException {
    // given
    comEntradas(4).close();

    // when
    try (IndiceEsparso indice =
        IndiceEsparso.abrir(arquivo, INTERVALO, 2L * INTERVALO + 1)) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(3);
      assertThat(Files.size(arquivo)).isEqualTo(3L * IndiceEsparso.BYTES_POR_ENTRADA);
    }
  }

  @Test
  void deveDescartarEntradaQueApontaExatamenteParaOFimDoSegmento() throws IOException {
    // given
    escreverEntradasCruas(new EntradaDeIndice(0, 0, 1000), new EntradaDeIndice(10, INTERVALO, 1010));

    // when
    try (IndiceEsparso indice = IndiceEsparso.abrir(arquivo, INTERVALO, INTERVALO)) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(1);
    }
  }

  @Test
  void deveAceitarEntradasComTimestampRepetido() throws IOException {
    // given
    escreverEntradasCruas(
        new EntradaDeIndice(0, 0, 1000), new EntradaDeIndice(10, INTERVALO, 1000));

    // when
    try (IndiceEsparso indice = abrir()) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(2);
      assertThat(indice.pisoPorTimestamp(1000)).isEqualTo(new EntradaDeIndice(10, INTERVALO, 1000));
    }
  }

  @Test
  void deveDescartarEntradaComOffsetOuPosicaoNaoCrescente() throws IOException {
    // given
    escreverEntradasCruas(
        new EntradaDeIndice(0, 0, 1000),
        new EntradaDeIndice(10, INTERVALO, 1010),
        new EntradaDeIndice(10, 2 * INTERVALO, 1020));

    // when
    try (IndiceEsparso indice = abrir()) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(2);
    }
  }

  @Test
  void deveDescartarEntradaComPosicaoNaoCrescente() throws IOException {
    // given
    escreverEntradasCruas(
        new EntradaDeIndice(0, 10, 1000), new EntradaDeIndice(10, 10, 1010));

    // when
    try (IndiceEsparso indice = abrir()) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(1);
    }
  }

  @Test
  void deveDescartarEntradaComTimestampDecrescente() throws IOException {
    // given
    escreverEntradasCruas(
        new EntradaDeIndice(0, 0, 1000), new EntradaDeIndice(10, INTERVALO, 999));

    // when
    try (IndiceEsparso indice = abrir()) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(1);
    }
  }

  @Test
  void deveDescartarEntradaComCampoNegativo() throws IOException {
    // given
    escreverEntradasCruas(new EntradaDeIndice(-1, 0, 1000));
    try (IndiceEsparso indice = abrir()) {
      assertThat(indice.quantidadeDeEntradas()).isZero();
    }

    // when
    escreverEntradasCruas(new EntradaDeIndice(0, -5, 1000));

    // then
    try (IndiceEsparso indice = abrir()) {
      assertThat(indice.quantidadeDeEntradas()).isZero();
    }
  }

  @Test
  void deveDescartarSobraDeArquivoTruncadoNoMeioDeUmaEntrada() throws IOException {
    // given
    comEntradas(2).close();
    byte[] conteudo = Files.readAllBytes(arquivo);
    Files.write(arquivo, Arrays.copyOf(conteudo, conteudo.length - 5));

    // when
    try (IndiceEsparso indice = abrir()) {

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(1);
      assertThat(Files.size(arquivo)).isEqualTo(IndiceEsparso.BYTES_POR_ENTRADA);
    }
  }

  @Test
  void deveContinuarGravandoDepoisDeDescartarSufixoInvalido() throws IOException {
    // given
    escreverEntradasCruas(
        new EntradaDeIndice(0, 0, 1000), new EntradaDeIndice(10, INTERVALO, 999));

    // when
    try (IndiceEsparso indice = abrir()) {
      indice.anotarSeAlcancouOIntervalo(10, INTERVALO, 1010);

      // then
      assertThat(indice.quantidadeDeEntradas()).isEqualTo(2);
      assertThat(indice.pisoPorOffset(10)).isEqualTo(new EntradaDeIndice(10, INTERVALO, 1010));
    }
  }

  private IndiceEsparso abrir() throws IOException {
    return IndiceEsparso.abrir(arquivo, INTERVALO, BYTES_DO_SEGMENTO);
  }

  private IndiceEsparso comEntradas(int quantidade) throws IOException {
    IndiceEsparso indice = abrir();
    for (int i = 0; i < quantidade; i++) {
      indice.anotarSeAlcancouOIntervalo(i * 10, i * INTERVALO, 1000 + i * 10);
    }
    return indice;
  }

  private void escreverEntradasCruas(EntradaDeIndice... entradas) throws IOException {
    ByteBuffer buffer =
        ByteBuffer.allocate(entradas.length * IndiceEsparso.BYTES_POR_ENTRADA);
    for (EntradaDeIndice entrada : entradas) {
      buffer.putInt(entrada.offsetRelativo()).putInt(entrada.posicao()).putLong(entrada.timestamp());
    }
    Files.write(arquivo, buffer.array());
  }
}
