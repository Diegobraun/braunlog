package com.braunlog;

import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.configuracaoPadrao;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

class LogTest {

  @TempDir Path diretorio;

  @Test
  void deveDevolverOsRegistrosNaOrdemEmQueForamAnexados() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));
      log.anexar(registro("b", "2"));

      // when
      List<RegistroLido> lidos = todos(log, Offset.ZERO);

      // then
      assertThat(lidos).extracting(RegistroLido::chave).containsExactly(bytes("a"), bytes("b"));
      assertThat(lidos).extracting(RegistroLido::valor).containsExactly(bytes("1"), bytes("2"));
    }
  }

  @Test
  void deveAtribuirOffsetsMonotonicosSemLacuna() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      List<Offset> anexados = new ArrayList<>();

      // when
      for (int i = 0; i < 20; i++) {
        anexados.add(log.anexar(registro("chave-" + i, "valor-" + i)));
      }

      // then
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(anexados);
      assertThat(anexados).isEqualTo(sequencia(0, 20));
      assertThat(log.ultimoOffset()).contains(Offset.de(19));
      assertThat(log.proximoOffset()).isEqualTo(Offset.de(20));
    }
  }

  @Test
  void deveLerAPartirDeUmOffsetArbitrario() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      for (int i = 0; i < 10; i++) {
        log.anexar(registro("chave-" + i, "valor-" + i));
      }

      // when
      List<Offset> lidos = offsets(todos(log, Offset.de(7)));

      // then
      assertThat(lidos).isEqualTo(sequencia(7, 10));
    }
  }

  @Test
  void deveDevolverNadaQuandoOOffsetPedidoEstaAlemDoFim() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));

      // when / then
      assertThat(todos(log, Offset.de(50))).isEmpty();
    }
  }

  @Test
  void deveInformarQueNaoHaUltimoOffsetQuandoOLogEstaVazio() {
    // given / when
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {

      // then
      assertThat(log.ultimoOffset()).isEmpty();
      assertThat(log.proximoOffset()).isEqualTo(Offset.ZERO);
      assertThat(todos(log, Offset.ZERO)).isEmpty();
    }
  }

  @Test
  void devePreservarTombstoneERegistroSemChave() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(Registro.tombstone(bytes("a")));
      log.anexar(Registro.semChave(bytes("evento")));

      // when
      List<RegistroLido> lidos = todos(log, Offset.ZERO);

      // then
      assertThat(lidos.get(0).ehTombstone()).isTrue();
      assertThat(lidos.get(0).chave()).isEqualTo(bytes("a"));
      assertThat(lidos.get(1).temChave()).isFalse();
      assertThat(lidos.get(1).valor()).isEqualTo(bytes("evento"));
    }
  }

  @Test
  void deveGravarOTimestampDoRelogioInjetado() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));

      // when
      RegistroLido lido = todos(log, Offset.ZERO).getFirst();

      // then
      assertThat(lido.timestamp()).isEqualTo(Suporte.INSTANTE_FIXO);
    }
  }

  @Test
  void deveRecusarRegistroMaiorQueOMaximoConfigurado() {
    // given
    ConfiguracaoLog configuracao = configuracaoPadrao().comTamanhoMaximoRegistro(64);
    try (Log log = Log.abrir(diretorio, configuracao)) {

      // when / then
      assertThatThrownBy(() -> log.anexar(Registro.semChave(new byte[100])))
          .isInstanceOf(ErroDeLog.class)
          .hasMessageContaining("excede o maximo");
    }
  }

  @Test
  void deveAceitarRegistroExatamenteNoTamanhoMaximoConfigurado() {
    // given
    Registro registro = Registro.semChave(new byte[100]);
    int tamanhoExato = FormatoRegistro.tamanhoCodificado(registro);
    ConfiguracaoLog configuracao = configuracaoPadrao().comTamanhoMaximoRegistro(tamanhoExato);

    // when
    try (Log log = Log.abrir(diretorio, configuracao)) {

      // then
      assertThat(log.anexar(registro)).isEqualTo(Offset.ZERO);
      assertThat(todos(log, Offset.ZERO)).hasSize(1);
    }
  }

  @Test
  void deveFalharAoAnexarDepoisDeFechado() {
    // given
    Log log = Log.abrir(diretorio, configuracaoPadrao());
    log.close();

    // when / then
    assertThatThrownBy(() -> log.anexar(registro("a", "1")))
        .isInstanceOf(ErroDeLog.class)
        .hasMessageContaining("falha ao anexar");
  }

  @Test
  void leitorFechadoNaoDeveContinuarLendo() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));
      Leitor leitor = log.lerDe(Offset.ZERO);
      leitor.close();

      // when / then
      assertThatThrownBy(leitor::hasNext).isInstanceOf(ErroDeLog.class);
    }
  }

  @Test
  void deveContinuarANumeracaoDeOffsetsAposReabrir() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));
      log.anexar(registro("b", "2"));
      log.forcarSincronizacao();
    }

    // when
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      Offset novo = log.anexar(registro("c", "3"));

      // then
      assertThat(novo).isEqualTo(Offset.de(2));
      assertThat(offsets(todos(log, Offset.ZERO))).isEqualTo(sequencia(0, 3));
    }
  }

  @Test
  void deveLancarQuandoPedirRegistroAlemDoFim() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoPadrao());
        Leitor leitor = log.lerDe(Offset.ZERO)) {

      // when / then
      assertThatThrownBy(leitor::next).isInstanceOf(NoSuchElementException.class);
    }
  }

  @Test
  void deveCriarODiretorioQuandoEleAindaNaoExiste() {
    // given
    Path aninhado = diretorio.resolve("dados").resolve("log");

    // when
    try (Log log = Log.abrir(aninhado, configuracaoPadrao())) {
      log.anexar(registro("a", "1"));

      // then
      assertThat(aninhado.resolve(Segmento.nomeDeArquivo(0))).exists();
    }
  }

  @Test
  void deveNomearOSegmentoPeloOffsetBaseComVinteDigitos() {
    // when
    String nome = Segmento.nomeDeArquivo(10_423);

    // then
    assertThat(nome).isEqualTo("00000000000000010423.log");
  }

  static List<RegistroLido> todos(Log log, Offset inicio) {
    try (Leitor leitor = log.lerDe(inicio)) {
      List<RegistroLido> lidos = new ArrayList<>();
      while (leitor.hasNext()) {
        lidos.add(leitor.next());
      }
      return lidos;
    }
  }

  static List<Offset> offsets(List<RegistroLido> registros) {
    return registros.stream().map(RegistroLido::offset).toList();
  }

  static List<Offset> sequencia(long inicio, long fimExclusivo) {
    List<Offset> offsets = new ArrayList<>();
    for (long i = inicio; i < fimExclusivo; i++) {
      offsets.add(Offset.de(i));
    }
    return offsets;
  }
}
