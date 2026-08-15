package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

class RetencaoTest {

  private static final long INICIO = 1_700_000_000_000L;
  private static final int REGISTROS_POR_SEGMENTO = 4;

  @TempDir Path diretorio;

  private final RelogioAjustavel relogio = RelogioAjustavel.em(INICIO);

  @Test
  void deveApagarSegmentosInteirosAteCaberNoTamanhoMaximo() {
    // given
    long tetoDeBytes = tamanhoDeSegmento() * 2;
    ConfiguracaoLog configuracao =
        configuracao().comPoliticaRetencao(PoliticaRetencao.porTamanhoTotal(tetoDeBytes));

    try (Log log = Log.abrir(diretorio, configuracao)) {
      // when
      anexar(log, 20);

      // then
      assertThat(log.quantidadeDeSegmentos())
          .describedAs("a rolagem ja apara o log, mas o segmento ativo cresce depois dela")
          .isEqualTo(3);

      // when
      log.aplicarRetencao();

      // then
      assertThat(log.bytes()).isLessThanOrEqualTo(tetoDeBytes);
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(2);
      assertThat(arquivosDeSegmento()).hasSize(2);
      assertThat(todos(log, Offset.ZERO).getFirst().offset()).isEqualTo(Offset.de(12));
    }
  }

  @Test
  void deveApagarSegmentoMaisVelhoQueAIdadeMaxima() {
    // given
    ConfiguracaoLog configuracao =
        configuracao().comPoliticaRetencao(PoliticaRetencao.porIdade(Duration.ofMinutes(10)));

    try (Log log = Log.abrir(diretorio, configuracao)) {
      anexar(log, REGISTROS_POR_SEGMENTO);
      relogio.avancar(Duration.ofHours(1));

      // when
      anexar(log, REGISTROS_POR_SEGMENTO * 2);

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(2);
      assertThat(todos(log, Offset.ZERO).getFirst().offset())
          .isEqualTo(Offset.de(REGISTROS_POR_SEGMENTO));
    }
  }

  @Test
  void nuncaDeveApagarOSegmentoAtivoMesmoQueAPoliticaMande() {
    // given
    ConfiguracaoLog configuracao =
        configuracao().comPoliticaRetencao(PoliticaRetencao.porTamanhoTotal(1));

    try (Log log = Log.abrir(diretorio, configuracao)) {
      // when
      anexar(log, 20);

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(1);
      assertThat(todos(log, Offset.ZERO)).isNotEmpty();
      assertThat(log.proximoOffset()).isEqualTo(Offset.de(20));
    }
  }

  @Test
  void deveAplicarRetencaoTambemNaAberturaDoLog() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexar(log, 20);
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(5);
    }

    // when
    ConfiguracaoLog comRetencao =
        configuracao().comPoliticaRetencao(PoliticaRetencao.porTamanhoTotal(tamanhoDeSegmento()));
    try (Log reaberto = Log.abrir(diretorio, comRetencao)) {

      // then
      assertThat(reaberto.quantidadeDeSegmentos()).isEqualTo(1);
      assertThat(arquivosDeSegmento()).hasSize(1);
    }
  }

  @Test
  void politicaCombinadaDeveApagarQuandoQualquerUmaMandar() {
    // given
    PoliticaRetencao combinada =
        PoliticaRetencao.combinada(
            PoliticaRetencao.porTamanhoTotal(Long.MAX_VALUE),
            PoliticaRetencao.porIdade(Duration.ofMinutes(10)));
    try (Log log = Log.abrir(diretorio, configuracao().comPoliticaRetencao(combinada))) {
      anexar(log, REGISTROS_POR_SEGMENTO);
      relogio.avancar(Duration.ofHours(1));

      // when
      anexar(log, REGISTROS_POR_SEGMENTO * 2);

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(2);
    }
  }

  @Test
  void politicaNenhumaNaoDeveApagarNada() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      // when
      anexar(log, 20);
      relogio.avancar(Duration.ofDays(365));
      log.aplicarRetencao();

      // then
      assertThat(log.quantidadeDeSegmentos()).isEqualTo(5);
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(LogTest.sequencia(0, 20));
    }
  }

  @Test
  void leitorPosicionadoNumSegmentoApagadoDeveSaltarParaOProximoSegmentoVivo() {
    // given
    ConfiguracaoLog configuracao =
        this.configuracao().comPoliticaRetencao(PoliticaRetencao.porTamanhoTotal(tamanhoDeSegmento()));
    try (Log log = Log.abrir(diretorio, configuracao);
        Leitor leitor = log.lerDe(Offset.ZERO)) {
      anexar(log, REGISTROS_POR_SEGMENTO);
      assertThat(leitor.next().offset()).isEqualTo(Offset.ZERO);

      // when
      anexar(log, REGISTROS_POR_SEGMENTO * 2);

      // then
      List<RegistroLido> resto = new ArrayList<>();
      while (leitor.hasNext()) {
        resto.add(leitor.next());
      }
      assertThat(offsets(resto)).isSorted();
      assertThat(resto.getLast().offset()).isEqualTo(Offset.de(REGISTROS_POR_SEGMENTO * 3 - 1));
    }
  }

  @Test
  void deveRecusarPoliticaComParametroInvalido() {
    assertThatThrownBy(() -> PoliticaRetencao.porTamanhoTotal(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positivo");
    assertThatThrownBy(() -> PoliticaRetencao.porIdade(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positiva");
    assertThatThrownBy(() -> PoliticaRetencao.porIdade(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(PoliticaRetencao::combinada)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ao menos uma");
  }

  private ConfiguracaoLog configuracao() {
    return ConfiguracaoLog.padrao(relogio)
        .comTamanhoMaximoRegistro(tamanhoDoRegistro())
        .comBytesMaximosPorSegmento(tamanhoDeSegmento());
  }

  private static long tamanhoDeSegmento() {
    return (long) tamanhoDoRegistro() * REGISTROS_POR_SEGMENTO;
  }

  private static int tamanhoDoRegistro() {
    return FormatoRegistro.tamanhoCodificado(registroDe(0));
  }

  private static Registro registroDe(int indice) {
    return Registro.de(bytes("chave-%02d".formatted(indice)), bytes("valor-%02d".formatted(indice)));
  }

  private static void anexar(Log log, int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      log.anexar(registroDe(i));
    }
  }

  private List<String> arquivosDeSegmento() {
    try (var arquivos = Files.list(diretorio)) {
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
