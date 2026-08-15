package com.braunlog;

import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.relogioFixo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TiposDeDominioTest {

  @Test
  void offsetDeveRecusarValorNegativo() {
    assertThatThrownBy(() -> Offset.de(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negativo");
  }

  @Test
  void offsetDeveOrdenarPeloValorEImprimirApenasONumero() {
    // given
    Offset menor = Offset.de(1);
    Offset maior = Offset.de(2);

    // then
    assertThat(menor).isLessThan(maior).isEqualTo(Offset.ZERO.proximo());
    assertThat(maior.proximo()).isEqualTo(Offset.de(3));
    assertThat(menor).hasToString("1");
  }

  @Test
  void registroDeveRecusarAusenciaDeChaveEDeValorAoMesmoTempo() {
    assertThatThrownBy(() -> new Registro(null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sem chave e sem valor");
  }

  @Test
  void registroDeveCompararPeloConteudoDosArraysENaoPelaIdentidade() {
    // given
    Registro um = Registro.de(bytes("a"), bytes("1"));
    Registro outro = Registro.de(bytes("a"), bytes("1"));
    Registro diferente = Registro.de(bytes("a"), bytes("2"));

    // then
    assertThat(um).isEqualTo(outro).hasSameHashCodeAs(outro);
    assertThat(um).isNotEqualTo(diferente).isNotEqualTo("outro tipo");
    assertThat(um.hashCode()).isNotEqualTo(diferente.hashCode());
    assertThat(um).hasToString("Registro[chave=[97], valor=[49]]");
  }

  @Test
  void registroDeveExporTombstoneEAusenciaDeChave() {
    assertThat(Registro.tombstone(bytes("a")).ehTombstone()).isTrue();
    assertThat(Registro.tombstone(bytes("a")).temChave()).isTrue();
    assertThat(Registro.semChave(bytes("v")).temChave()).isFalse();
    assertThat(Registro.semChave(bytes("v")).ehTombstone()).isFalse();
  }

  @Test
  void registroLidoDeveCompararPeloConteudo() {
    // given
    RegistroLido um = new RegistroLido(Offset.ZERO, 10, bytes("a"), bytes("1"));
    RegistroLido igual = new RegistroLido(Offset.ZERO, 10, bytes("a"), bytes("1"));

    // then
    assertThat(um).isEqualTo(igual).hasSameHashCodeAs(igual);
    assertThat(um).isNotEqualTo(new RegistroLido(Offset.de(1), 10, bytes("a"), bytes("1")));
    assertThat(um).isNotEqualTo(new RegistroLido(Offset.ZERO, 11, bytes("a"), bytes("1")));
    assertThat(um).isNotEqualTo(new RegistroLido(Offset.ZERO, 10, bytes("b"), bytes("1")));
    assertThat(um).isNotEqualTo(new RegistroLido(Offset.ZERO, 10, bytes("a"), bytes("2")));
    assertThat(um).isNotEqualTo("outro tipo");
    assertThat(um.toString()).contains("offset=0", "timestamp=10");
  }

  @Test
  void configuracaoDeveRecusarRelogioNuloETamanhoMaximoAbsurdo() {
    assertThatThrownBy(() -> new ConfiguracaoLog(null, 1024))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ConfiguracaoLog.padrao(relogioFixo()).comTamanhoMaximoRegistro(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pequeno demais");
  }

  @Test
  void configuracaoPadraoDeveUsarOTetoDeUmMegabyte() {
    assertThat(ConfiguracaoLog.padrao(relogioFixo()).tamanhoMaximoRegistro())
        .isEqualTo(ConfiguracaoLog.TAMANHO_MAXIMO_REGISTRO_PADRAO);
  }
}
