package com.braunlog;

import static com.braunlog.LogTest.offsets;
import static com.braunlog.LogTest.sequencia;
import static com.braunlog.LogTest.todos;
import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.configuracaoPadrao;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Matar o processo e derrubar o sistema operacional sao coisas diferentes: o page cache pertence ao
 * sistema operacional e sobrevive ao {@code kill -9}. Estes testes provam a primeira metade da
 * tabela de durabilidade do README; a segunda metade — perder dados quando o sistema operacional
 * cai — nao tem como ser provada de dentro de um teste, e por isso esta declarada e nao afirmada.
 */
class QuedaDeProcessoTest {

  private static final int REGISTROS = 200;

  @TempDir Path diretorio;

  @ParameterizedTest
  @ValueSource(strings = {"nenhum", "a-cada-append"})
  void registroConfirmadoDeveSobreviverAoKillDoProcessoEmQualquerModo(String modo)
      throws IOException, InterruptedException, URISyntaxException {
    // given
    matarDepoisDeEscrever(modo);

    // when
    try (Log reaberto = Log.abrir(diretorio, configuracaoPadrao())) {
      List<RegistroLido> lidos = todos(reaberto, Offset.ZERO);

      // then
      assertThat(offsets(lidos)).isEqualTo(sequencia(0, REGISTROS));
      assertThat(lidos.getLast().chave()).isEqualTo(bytes("chave-" + (REGISTROS - 1)));
      assertThat(reaberto.anexar(Suporte.registro("depois", "x")))
          .isEqualTo(Offset.de(REGISTROS));
    }
  }

  private void matarDepoisDeEscrever(String modo)
      throws IOException, InterruptedException, URISyntaxException {
    Process processo =
        new ProcessBuilder(
                caminhoDoJava(),
                "-cp",
                classpathMinimo(),
                EscritorEmProcessoSeparado.class.getName(),
                diretorio.toString(),
                String.valueOf(REGISTROS),
                modo,
                String.valueOf(Suporte.INSTANTE_FIXO))
            .redirectErrorStream(true)
            .start();

    try (BufferedReader saida = processo.inputReader()) {
      assertThat(saida.readLine()).isEqualTo(EscritorEmProcessoSeparado.AVISO_DE_PRONTO);
    }
    processo.destroyForcibly();
    assertThat(processo.waitFor()).isNotZero();
  }

  private static String caminhoDoJava() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String classpathMinimo() throws URISyntaxException {
    Set<String> caminhos = new LinkedHashSet<>();
    caminhos.add(origemDe(Log.class));
    caminhos.add(origemDe(EscritorEmProcessoSeparado.class));
    return String.join(File.pathSeparator, caminhos);
  }

  private static String origemDe(Class<?> classe) throws URISyntaxException {
    return Path.of(classe.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }
}
