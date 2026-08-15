package com.braunlog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.braunlog.formato.FormatoRegistro;

/**
 * Conteudo de segmento montado byte a byte pelo proprio codificador, para que os testes de
 * truncamento e corrupcao saibam exatamente onde cada registro comeca e termina.
 */
final class AmostraDeLog {

  static final List<Registro> REGISTROS =
      List.of(
          Suporte.registro("a", "1"),
          Suporte.registro("bb", "22"),
          Registro.tombstone(Suporte.bytes("a")),
          Registro.semChave(Suporte.bytes("evento")),
          Suporte.registro("ccc", "333"),
          Suporte.registro("d", "4444"));

  private AmostraDeLog() {}

  static byte[] bytes() {
    ByteBuffer buffer = ByteBuffer.allocate(tamanhoTotal());
    for (int i = 0; i < REGISTROS.size(); i++) {
      FormatoRegistro.codificar(buffer, REGISTROS.get(i), i, Suporte.INSTANTE_FIXO);
    }
    return buffer.array();
  }

  static List<RegistroLido> esperados() {
    List<RegistroLido> lidos = new ArrayList<>();
    for (int i = 0; i < REGISTROS.size(); i++) {
      Registro registro = REGISTROS.get(i);
      lidos.add(
          new RegistroLido(
              Offset.de(i), Suporte.INSTANTE_FIXO, registro.chave(), registro.valor()));
    }
    return lidos;
  }

  /** Quantos registros estao integralmente contidos nos primeiros {@code bytes} do arquivo. */
  static int registrosCompletosAte(int bytes) {
    int completos = 0;
    int fim = 0;
    for (Registro registro : REGISTROS) {
      fim += FormatoRegistro.tamanhoCodificado(registro);
      if (fim > bytes) {
        return completos;
      }
      completos++;
    }
    return completos;
  }

  static int tamanhoDosPrimeiros(int quantidade) {
    return REGISTROS.subList(0, quantidade).stream()
        .mapToInt(FormatoRegistro::tamanhoCodificado)
        .sum();
  }

  static int tamanhoTotal() {
    return REGISTROS.stream().mapToInt(FormatoRegistro::tamanhoCodificado).sum();
  }
}
