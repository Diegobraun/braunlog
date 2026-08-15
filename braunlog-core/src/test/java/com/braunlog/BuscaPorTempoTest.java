package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.sequencia;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.registro;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

class BuscaPorTempoTest {

  private static final long INICIO = 1_700_000_000_000L;
  private static final Duration PASSO = Duration.ofMillis(10);

  @TempDir Path diretorio;

  private final RelogioAjustavel relogio = RelogioAjustavel.em(INICIO);

  @Test
  void deveDevolverOPrimeiroOffsetComTimestampMaiorOuIgualAoInstantePedido() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexarComPasso(log, 10);

      // when / then
      assertThat(log.primeiroOffsetDesde(instanteDoPasso(0))).contains(Offset.ZERO);
      assertThat(log.primeiroOffsetDesde(instanteDoPasso(4))).contains(Offset.de(4));
      assertThat(log.primeiroOffsetDesde(Instant.ofEpochMilli(INICIO - 1))).contains(Offset.ZERO);
    }
  }

  @Test
  void deveArredondarParaOProximoRegistroQuandoOInstanteCaiEntreDois() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexarComPasso(log, 10);

      // when
      Instant entreODoisEOTres = instanteDoPasso(2).plusMillis(PASSO.toMillis() / 2);

      // then
      assertThat(log.primeiroOffsetDesde(entreODoisEOTres)).contains(Offset.de(3));
    }
  }

  @Test
  void deveDevolverVazioQuandoNenhumRegistroAlcancaOInstante() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexarComPasso(log, 5);

      // when / then
      assertThat(log.primeiroOffsetDesde(instanteDoPasso(100))).isEmpty();
    }
  }

  @Test
  void deveAtravessarSegmentosNaBuscaPorTempo() {
    // given
    try (Log log = Log.abrir(diretorio, configuracaoDeSegmentoPequeno())) {
      anexarComPasso(log, 20);
      assertThat(log.quantidadeDeSegmentos()).isGreaterThan(2);

      // when / then
      for (int passo = 0; passo < 20; passo++) {
        assertThat(log.primeiroOffsetDesde(instanteDoPasso(passo))).contains(Offset.de(passo));
      }
    }
  }

  @Test
  void lerDesdeDeveComecarNoRegistroCertoEIrAteOFim() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexarComPasso(log, 10);

      // when
      List<RegistroLido> lidos = consumir(log.lerDesde(instanteDoPasso(6)));

      // then
      assertThat(offsets(lidos)).isEqualTo(sequencia(6, 10));
    }
  }

  @Test
  void lerDesdeDeveDevolverLeitorVazioQuandoOInstanteEDepoisDeTudo() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexarComPasso(log, 3);

      // when / then
      assertThat(consumir(log.lerDesde(instanteDoPasso(50)))).isEmpty();
    }
  }

  @Test
  void deveGravarTimestampNaoDecrescenteQuandoORelogioVoltaAtras() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(registro("a", "1"));
      relogio.voltar(Duration.ofHours(1));

      // when
      log.anexar(registro("b", "2"));

      // then
      List<RegistroLido> lidos = todos(log, Offset.ZERO);
      assertThat(lidos.get(1).timestamp()).isEqualTo(lidos.get(0).timestamp());
      assertThat(log.primeiroOffsetDesde(Instant.ofEpochMilli(INICIO))).contains(Offset.ZERO);
    }
  }

  @Test
  void deveManterOTimestampNaoDecrescenteDepoisDeReabrir() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      anexarComPasso(log, 3);
    }
    relogio.voltar(Duration.ofHours(1));

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracao())) {
      reaberto.anexar(registro("depois", "x"));

      // then
      List<RegistroLido> lidos = todos(reaberto, Offset.ZERO);
      assertThat(lidos.getLast().timestamp()).isEqualTo(lidos.get(2).timestamp());
    }
  }

  private ConfiguracaoLog configuracao() {
    return ConfiguracaoLog.padrao(relogio);
  }

  private ConfiguracaoLog configuracaoDeSegmentoPequeno() {
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(registro("chave-00", "valor-00"));
    return configuracao()
        .comTamanhoMaximoRegistro(tamanhoDoRegistro)
        .comBytesMaximosPorSegmento(tamanhoDoRegistro * 4L);
  }

  private void anexarComPasso(Log log, int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      log.anexar(registro("chave-%02d".formatted(i), "valor-%02d".formatted(i)));
      relogio.avancar(PASSO);
    }
  }

  private static Instant instanteDoPasso(int passo) {
    return Instant.ofEpochMilli(INICIO + passo * PASSO.toMillis());
  }

  private static List<RegistroLido> consumir(Leitor leitor) {
    try (leitor) {
      List<RegistroLido> lidos = new ArrayList<>();
      while (leitor.hasNext()) {
        lidos.add(leitor.next());
      }
      return lidos;
    }
  }
}
