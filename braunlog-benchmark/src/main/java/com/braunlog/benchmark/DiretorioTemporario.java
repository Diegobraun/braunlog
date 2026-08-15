package com.braunlog.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Diretorio descartavel para o benchmark nao depender de caminho fixo nem sujar o repositorio. */
final class DiretorioTemporario {

  private final Path caminho;

  private DiretorioTemporario(Path caminho) {
    this.caminho = caminho;
  }

  static DiretorioTemporario criar(String prefixo) throws IOException {
    return new DiretorioTemporario(Files.createTempDirectory("braunlog-" + prefixo));
  }

  Path caminho() {
    return caminho;
  }

  void apagar() throws IOException {
    try (Stream<Path> caminhos = Files.walk(caminho)) {
      for (Path alvo : caminhos.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(alvo);
      }
    }
  }
}
