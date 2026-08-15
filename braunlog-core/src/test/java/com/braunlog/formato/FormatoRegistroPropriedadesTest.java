package com.braunlog.formato;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;

import com.braunlog.Registro;
import com.braunlog.RegistroLido;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class FormatoRegistroPropriedadesTest {

  @Property
  void oRegistroDecodificadoDeveSerIdenticoAoCodificado(
      @ForAll("registros") Registro registro,
      @ForAll @IntRange(min = 0, max = 1_000_000) int offsetRelativo,
      @ForAll long timestamp,
      @ForAll @IntRange(min = 0, max = 1_000_000) int offsetBase) {
    // given
    ByteBuffer buffer = ByteBuffer.allocate(FormatoRegistro.tamanhoCodificado(registro));

    // when
    FormatoRegistro.codificar(buffer, registro, offsetRelativo, timestamp);
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(buffer.flip(), offsetBase);

    // then
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Sucesso.class);
    RegistroLido lido = ((ResultadoDecodificacao.Sucesso) resultado).registro();
    assertThat(lido.registro()).isEqualTo(registro);
    assertThat(lido.timestamp()).isEqualTo(timestamp);
    assertThat(lido.offset().valor()).isEqualTo((long) offsetBase + offsetRelativo);
  }

  @Property
  void qualquerBitInvertidoNoRegistroDeveSerRecusado(
      @ForAll("registros") Registro registro, @ForAll @IntRange(min = 0, max = 7) int bit) {
    // given
    ByteBuffer buffer = ByteBuffer.allocate(FormatoRegistro.tamanhoCodificado(registro));
    FormatoRegistro.codificar(buffer, registro, 0, 0L);
    buffer.flip();

    for (int posicao = 0; posicao < buffer.limit(); posicao++) {
      byte original = buffer.get(posicao);
      buffer.put(posicao, (byte) (original ^ (1 << bit)));

      // when
      ResultadoDecodificacao resultado = FormatoRegistro.decodificar(buffer, 0);

      // then
      assertThat(resultado)
          .describedAs("bit %d da posicao %d", bit, posicao)
          .isNotInstanceOf(ResultadoDecodificacao.Sucesso.class);
      buffer.put(posicao, original);
    }
  }

  @Provide
  Arbitrary<Registro> registros() {
    Arbitrary<Registro> comChaveEValor = Combinators.combine(blocos(), blocos()).as(Registro::de);
    Arbitrary<Registro> tombstones = blocos().map(Registro::tombstone);
    Arbitrary<Registro> semChave = blocos().map(Registro::semChave);
    return Arbitraries.oneOf(comChaveEValor, tombstones, semChave);
  }

  private static Arbitrary<byte[]> blocos() {
    return Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(32);
  }
}
