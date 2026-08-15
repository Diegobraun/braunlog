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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

class SegmentacaoTest {

  private static final int REGISTROS_POR_SEGMENTO = 4;

  @TempDir Path diretorio;

  @Test
  void deveRolarParaUmNovoSegmentoAoAlcancarOTamanhoMaximo() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {

      // when
      anexar(log, 10);

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(3);
      assertThat(nomesDeSegmento())
          .containsExactly(
              Segmento.nomeDeArquivo(0), Segmento.nomeDeArquivo(4), Segmento.nomeDeArquivo(8));
    }
  }

  @Test
  void deveNomearCadaSegmentoPeloOffsetDoPrimeiroRegistroQueEleContem() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {
      anexar(log, 10);

      // when
      List<RegistroLido> lidos = todos(log, Offset.de(4));

      // then
      assertThat(lidos.getFirst().offset()).isEqualTo(Offset.de(4));
      assertThat(nomesDeSegmento()).contains(Segmento.nomeDeArquivo(4));
    }
  }

  @Test
  void deveManterOffsetContinuoESemLacunaEntreSegmentos() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {

      // when
      List<Offset> anexados = anexar(log, 30);

      // then
      assertThat(anexados).isEqualTo(sequencia(0, 30));
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(anexados);
      assertThat(log.quantidadeDeSegmentos()).isGreaterThan(1);
    }
  }

  @Test
  void deveLerAPartirDeQualquerOffsetAtravessandoSegmentos() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {
      anexar(log, 20);

      // when / then
      for (int inicio = 0; inicio <= 20; inicio++) {
        assertThat(offsets(todos(log, Offset.de(inicio)))).isEqualTo(sequencia(inicio, 20));
      }
    }
  }

  @Test
  void leitorDeveAlcancarSegmentoCriadoDepoisDeEleTerComecadoALer() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno());
        Leitor leitor = log.lerDe(Offset.ZERO)) {
      anexar(log, REGISTROS_POR_SEGMENTO);
      consumir(leitor);
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(1);

      // when
      anexar(log, REGISTROS_POR_SEGMENTO);

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(2);
      assertThat(offsets(consumir(leitor)))
          .isEqualTo(sequencia(REGISTROS_POR_SEGMENTO, 2L * REGISTROS_POR_SEGMENTO));
    }
  }

  @Test
  void deveReabrirComVariosSegmentosEContinuarANumeracao() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {
      anexar(log, 10);
    }

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {

      // then
      assertThat(reaberto.quantidadeDeSegmentos()).isEqualTo(3);
      assertThat(reaberto.proximoOffset()).isEqualTo(Offset.de(10));
      assertThat(reaberto.anexar(registro("novo", "x"))).isEqualTo(Offset.de(10));
      assertThat(offsets(todos(reaberto, Offset.ZERO))).isEqualTo(sequencia(0, 11));
    }
  }

  @Test
  void naoDeveRolarSegmentoVazioMesmoQueORegistroPasseDoLimite() {
    // given
    ConfiguracaoLog configuracao =
        configuracaoPadrao().comTamanhoMaximoRegistro(200).comBytesMaximosPorSegmento(200);
    try (Log log = Log.abrir(diretorio, configuracao)) {

      // when
      log.anexar(Registro.semChave(new byte[150]));
      log.anexar(Registro.semChave(new byte[150]));

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(2);
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, 2));
    }
  }

  @Test
  void deveIgnorarArquivoComNomeForaDaConvencaoNoDiretorio() throws IOException {
    // given
    Files.writeString(diretorio.resolve("anotacao.txt"), "nao sou segmento");
    Files.writeString(diretorio.resolve("rascunho.log"), "nem eu");

    // when
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(1);
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, 1));
    }
  }

  private ConfiguracaoLog configuracaoDeSegmentoPequeno() {
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(registro("chave-00", "valor-00"));
    return configuracaoPadrao()
        .comTamanhoMaximoRegistro(tamanhoDoRegistro)
        .comBytesMaximosPorSegmento((long) tamanhoDoRegistro * REGISTROS_POR_SEGMENTO);
  }

  private static List<Offset> anexar(Log log, int quantidade) {
    return IntStream.range(0, quantidade)
        .mapToObj(i -> log.anexar(registro("chave-%02d".formatted(i), "valor-%02d".formatted(i))))
        .toList();
  }

  private static List<RegistroLido> consumir(Leitor leitor) {
    List<RegistroLido> lidos = new ArrayList<>();
    while (leitor.hasNext()) {
      lidos.add(leitor.next());
    }
    return lidos;
  }

  private List<String> nomesDeSegmento() {
    try (Stream<Path> arquivos = Files.list(diretorio)) {
      return arquivos
          .map(arquivo -> arquivo.getFileName().toString())
          .filter(nome -> nome.endsWith(Segmento.SUFIXO_SEGMENTO))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
