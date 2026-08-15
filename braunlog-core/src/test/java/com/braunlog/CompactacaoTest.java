package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

class CompactacaoTest {

  private static final int REGISTROS_POR_SEGMENTO = 4;

  @TempDir Path diretorio;

  @Test
  void deveManterApenasOUltimoValorDeCadaChave() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("b", "1"));
      log.anexar(par("a", "2"));
      log.anexar(par("a", "3"));
      anexarEnchimento(log, 8);

      // when
      RelatorioDeCompactacao relatorio = log.compactar();

      // then
      assertThat(relatorio.segmentosReescritos()).isPositive();
      assertThat(relatorio.bytesLiberados()).isPositive();
      assertThat(materializar(log))
          .containsEntry("a", "3")
          .containsEntry("b", "1");
      assertThat(valoresDaChave(log, "a")).containsExactly("3");
    }
  }

  @Test
  void deveManterOTombstoneComoUltimoRegistroDaChave() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));
      log.anexar(Registro.tombstone(bytes("a")));
      anexarEnchimento(log, 8);

      // when
      log.compactar();

      // then
      assertThat(materializar(log)).doesNotContainKey("a");
      List<RegistroLido> daChave = registrosDaChave(log, "a");
      assertThat(daChave).hasSize(1);
      assertThat(daChave.getFirst().ehTombstone()).isTrue();
    }
  }

  @Test
  void devePreservarOsOffsetsOriginaisDeixandoLacunas() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));
      log.anexar(par("b", "1"));
      log.anexar(par("a", "3"));
      anexarEnchimento(log, 8);

      // when
      log.compactar();

      // then
      List<Offset> lidos = offsets(todos(log, Offset.ZERO));
      assertThat(lidos).isSorted().doesNotHaveDuplicates();
      assertThat(lidos).doesNotContain(Offset.ZERO, Offset.de(1));
      assertThat(lidos).contains(Offset.de(2), Offset.de(3));
    }
  }

  @Test
  void devePreservarRegistroSemChavePorqueEleNaoTemComoSerSubstituido() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(Registro.semChave(bytes("evento-1")));
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));
      log.anexar(Registro.semChave(bytes("evento-2")));
      anexarEnchimento(log, 8);

      // when
      log.compactar();

      // then
      List<RegistroLido> semChave = todos(log, Offset.ZERO).stream().filter(r -> !r.temChave()).toList();
      assertThat(semChave).hasSize(2);
      assertThat(semChave.getFirst().valor()).isEqualTo(bytes("evento-1"));
    }
  }

  @Test
  void naoDeveTocarNoSegmentoAtivo() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));

      // when
      RelatorioDeCompactacao relatorio = log.compactar();

      // then
      assertThat(relatorio).isEqualTo(new RelatorioDeCompactacao(0, 0, 0));
      assertThat(materializar(log)).containsEntry("a", "2");
      assertThat(todos(log, Offset.ZERO)).hasSize(2);
    }
  }

  @Test
  void naoDeveReescreverSegmentoAbaixoDoDirtyRatio() {
    // given
    ConfiguracaoLog configuracao = configuracao().comDirtyRatioMinimo(0.9);
    try (Log log = Log.abrir(diretorio, configuracao)) {
      for (int i = 0; i < 12; i++) {
        log.anexar(par("chave-" + i, "valor"));
      }

      // when
      RelatorioDeCompactacao relatorio = log.compactar();

      // then
      assertThat(relatorio.segmentosReescritos()).isZero();
      assertThat(todos(log, Offset.ZERO)).hasSize(12);
    }
  }

  @Test
  void deveSobreviverAReaberturaDepoisDeCompactar() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));
      anexarEnchimento(log, 10);
      log.compactar();
    }

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracao())) {

      // then
      assertThat(materializar(reaberto)).containsEntry("a", "2");
      assertThat(offsets(todos(reaberto, Offset.ZERO))).isSorted();
      assertThat(reaberto.anexar(par("c", "1"))).isEqualTo(Offset.de(12));
    }
  }

  @Test
  void naoDeveDeixarArquivoTemporarioParaTras() throws IOException {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));
      anexarEnchimento(log, 10);

      // when
      log.compactar();

      // then
      assertThat(nomesDeArquivo()).noneMatch(nome -> nome.contains("compactando"));
    }
  }

  @Test
  void leitorEmAndamentoDeveSobreviverATrocaDoSegmento() {
    // given
    try (Log log = Log.abrir(diretorio, configuracao())) {
      log.anexar(par("a", "1"));
      log.anexar(par("a", "2"));
      log.anexar(par("b", "1"));
      anexarEnchimento(log, 9);

      try (Leitor leitor = log.lerDe(Offset.ZERO)) {
        assertThat(leitor.next().offset()).isEqualTo(Offset.ZERO);

        // when
        log.compactar();

        // then
        List<Offset> resto = new ArrayList<>();
        while (leitor.hasNext()) {
          resto.add(leitor.next().offset());
        }
        assertThat(resto).isSorted().doesNotHaveDuplicates();
        assertThat(resto.getLast()).isEqualTo(Offset.de(11));
      }
    }
  }

  private ConfiguracaoLog configuracao() {
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(par("chave-000", "valor-000"));
    return Suporte.configuracaoPadrao()
        .comTamanhoMaximoRegistro(tamanhoDoRegistro * 2)
        .comBytesMaximosPorSegmento((long) tamanhoDoRegistro * REGISTROS_POR_SEGMENTO)
        .comDirtyRatioMinimo(0.01);
  }

  private static Registro par(String chave, String valor) {
    return Registro.de(bytes(chave), bytes(valor));
  }

  private static void anexarEnchimento(Log log, int quantidade) {
    for (int i = 0; i < quantidade; i++) {
      log.anexar(par("enchimento-%03d".formatted(i), "valor"));
    }
  }

  static Map<String, String> materializar(Log log) {
    Map<String, String> visao = new LinkedHashMap<>();
    for (RegistroLido lido : todos(log, Offset.ZERO)) {
      if (!lido.temChave()) {
        continue;
      }
      String chave = new String(lido.chave(), StandardCharsets.UTF_8);
      if (lido.ehTombstone()) {
        visao.remove(chave);
      } else {
        visao.put(chave, new String(lido.valor(), StandardCharsets.UTF_8));
      }
    }
    return visao;
  }

  private static List<RegistroLido> registrosDaChave(Log log, String chave) {
    return todos(log, Offset.ZERO).stream()
        .filter(RegistroLido::temChave)
        .filter(lido -> Arrays.equals(lido.chave(), bytes(chave)))
        .toList();
  }

  private static List<String> valoresDaChave(Log log, String chave) {
    return registrosDaChave(log, chave).stream()
        .map(lido -> new String(lido.valor(), StandardCharsets.UTF_8))
        .toList();
  }

  private List<String> nomesDeArquivo() throws IOException {
    try (Stream<Path> arquivos = Files.list(diretorio)) {
      return arquivos.map(arquivo -> arquivo.getFileName().toString()).toList();
    }
  }
}
