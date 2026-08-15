package com.braunlog;

import static com.braunlog.CompactacaoTest.materializar;
import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.configuracaoPadrao;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.braunlog.formato.FormatoRegistro;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * A compactacao e comparada contra um {@link HashMap} construido em memoria com a mesma sequencia
 * aleatoria de escrita e remocao. Se as duas visoes divergirem em qualquer sequencia, a
 * implementacao esta errada — nao o teste.
 */
class CompactacaoPropriedadesTest {

  private static final int REGISTROS_POR_SEGMENTO = 4;

  private Path diretorio;

  sealed interface Operacao {
    record Escrever(String chave, String valor) implements Operacao {}

    record Remover(String chave) implements Operacao {}
  }

  @BeforeTry
  void criarDiretorio() throws IOException {
    diretorio = Files.createTempDirectory("braunlog-compactacao");
  }

  @AfterTry
  void apagarDiretorio() throws IOException {
    try (var caminhos = Files.walk(diretorio)) {
      caminhos.sorted(Comparator.reverseOrder()).forEach(CompactacaoPropriedadesTest::apagar);
    }
  }

  @Property(tries = 200)
  void compactacaoDevePreservarOUltimoValorDeCadaChave(
      @ForAll("operacoes") List<Operacao> operacoes) {
    // given
    Map<String, String> referencia = new HashMap<>();
    try (Log log = Log.abrir(diretorio, configuracao())) {
      for (Operacao operacao : operacoes) {
        switch (operacao) {
          case Operacao.Escrever(String chave, String valor) -> {
            log.anexar(Registro.de(bytes(chave), bytes(valor)));
            referencia.put(chave, valor);
          }
          case Operacao.Remover(String chave) -> {
            log.anexar(Registro.tombstone(bytes(chave)));
            referencia.remove(chave);
          }
        }
      }
      Map<String, String> antes = materializar(log);

      // when
      log.compactar();

      // then
      assertThat(antes).containsExactlyInAnyOrderEntriesOf(referencia);
      assertThat(materializar(log)).containsExactlyInAnyOrderEntriesOf(referencia);
      assertThat(offsets(todos(log, Offset.ZERO))).isSorted().doesNotHaveDuplicates();
    }
  }

  @Property(tries = 50)
  void compactarDuasVezesSeguidasNaoDeveMudarNada(@ForAll("operacoes") List<Operacao> operacoes) {
    try (Log log = Log.abrir(diretorio, configuracao())) {
      // given
      for (Operacao operacao : operacoes) {
        switch (operacao) {
          case Operacao.Escrever(String chave, String valor) ->
              log.anexar(Registro.de(bytes(chave), bytes(valor)));
          case Operacao.Remover(String chave) -> log.anexar(Registro.tombstone(bytes(chave)));
        }
      }
      log.compactar();
      List<RegistroLido> depoisDaPrimeira = todos(log, Offset.ZERO);

      // when
      log.compactar();

      // then
      assertThat(todos(log, Offset.ZERO)).isEqualTo(depoisDaPrimeira);
    }
  }

  @Property(tries = 50)
  void compactacaoDeveSobreviverAReabertura(@ForAll("operacoes") List<Operacao> operacoes) {
    // given
    Map<String, String> referencia = new HashMap<>();
    try (Log log = Log.abrir(diretorio, configuracao())) {
      for (Operacao operacao : operacoes) {
        switch (operacao) {
          case Operacao.Escrever(String chave, String valor) -> {
            log.anexar(Registro.de(bytes(chave), bytes(valor)));
            referencia.put(chave, valor);
          }
          case Operacao.Remover(String chave) -> {
            log.anexar(Registro.tombstone(bytes(chave)));
            referencia.remove(chave);
          }
        }
      }
      log.compactar();
    }

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracao())) {

      // then
      assertThat(materializar(reaberto)).containsExactlyInAnyOrderEntriesOf(referencia);
    }
  }

  @Provide
  Arbitrary<List<Operacao>> operacoes() {
    Arbitrary<String> chaves = Arbitraries.of("a", "b", "c", "d", "e");
    Arbitrary<Operacao> escritas =
        Combinators.combine(chaves, Arbitraries.strings().alpha().ofMaxLength(6))
            .as(Operacao.Escrever::new);
    Arbitrary<Operacao> remocoes = chaves.map(Operacao.Remover::new);
    return Arbitraries.frequencyOf(
            Tuple.of(3, escritas), Tuple.of(1, remocoes))
        .list()
        .ofMinSize(1)
        .ofMaxSize(60);
  }

  private ConfiguracaoLog configuracao() {
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(Registro.de(bytes("a"), bytes("aaaaaa")));
    return configuracaoPadrao()
        .comTamanhoMaximoRegistro(tamanhoDoRegistro * 2)
        .comBytesMaximosPorSegmento((long) tamanhoDoRegistro * REGISTROS_POR_SEGMENTO)
        .comDirtyRatioMinimo(0.01);
  }

  private static void apagar(Path caminho) {
    try {
      Files.delete(caminho);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
