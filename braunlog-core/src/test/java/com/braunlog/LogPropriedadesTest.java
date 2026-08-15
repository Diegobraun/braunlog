package com.braunlog;

import static com.braunlog.Suporte.configuracaoPadrao;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

class LogPropriedadesTest {

  private Path diretorio;

  @BeforeTry
  void criarDiretorio() throws IOException {
    diretorio = Files.createTempDirectory("braunlog-propriedade");
  }

  @AfterTry
  void apagarDiretorio() throws IOException {
    try (var caminhos = Files.walk(diretorio)) {
      caminhos.sorted(Comparator.reverseOrder()).forEach(LogPropriedadesTest::apagar);
    }
  }

  @Property(tries = 200)
  void tudoQueFoiAnexadoDeveVoltarIgualNaMesmaOrdem(
      @ForAll("listasDeRegistros") List<Registro> registros) {
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      // given
      List<Offset> anexados = new ArrayList<>();

      // when
      for (Registro registro : registros) {
        anexados.add(log.anexar(registro));
      }
      List<RegistroLido> lidos = LogTest.todos(log, Offset.ZERO);

      // then
      assertThat(lidos).extracting(RegistroLido::registro).isEqualTo(registros);
      assertThat(LogTest.offsets(lidos)).isEqualTo(anexados);
      assertThat(anexados).isEqualTo(LogTest.sequencia(0, registros.size()));
    }
  }

  @Property(tries = 100)
  void lerAPartirDeQualquerOffsetDeveDevolverExatamenteOSufixo(
      @ForAll("listasDeRegistros") List<Registro> registros) {
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      // given
      registros.forEach(log::anexar);
      List<RegistroLido> todos = LogTest.todos(log, Offset.ZERO);

      for (int inicio = 0; inicio <= registros.size(); inicio++) {
        // when
        List<RegistroLido> sufixo = LogTest.todos(log, Offset.de(inicio));

        // then
        assertThat(sufixo).isEqualTo(todos.subList(inicio, todos.size()));
      }
    }
  }

  @Property(tries = 50)
  void reabrirDeveEncontrarOMesmoConteudoEContinuarANumeracao(
      @ForAll("listasDeRegistros") List<Registro> registros) {
    // given
    List<RegistroLido> antes;
    try (Log log = Log.abrir(diretorio, configuracaoPadrao())) {
      registros.forEach(log::anexar);
      antes = LogTest.todos(log, Offset.ZERO);
    }

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracaoPadrao())) {
      List<RegistroLido> depois = LogTest.todos(reaberto, Offset.ZERO);

      // then
      assertThat(depois).isEqualTo(antes);
      assertThat(reaberto.proximoOffset()).isEqualTo(Offset.de(registros.size()));
    }
  }

  @Provide
  Arbitrary<List<Registro>> listasDeRegistros() {
    return registros().list().ofMaxSize(30);
  }

  private Arbitrary<Registro> registros() {
    Arbitrary<Registro> comChaveEValor = Combinators.combine(blocos(), blocos()).as(Registro::de);
    Arbitrary<Registro> tombstones = blocos().map(Registro::tombstone);
    Arbitrary<Registro> semChave = blocos().map(Registro::semChave);
    return Arbitraries.oneOf(comChaveEValor, tombstones, semChave);
  }

  private Arbitrary<byte[]> blocos() {
    return Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(24);
  }

  private static void apagar(Path caminho) {
    try {
      Files.delete(caminho);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
