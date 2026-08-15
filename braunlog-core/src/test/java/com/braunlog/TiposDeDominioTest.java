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
  void configuracaoDeveRecusarValoresIncoerentes() {
    ConfiguracaoLog padrao = ConfiguracaoLog.padrao(relogioFixo());

    assertThatThrownBy(() -> new ConfiguracaoLog(null, 1024, 1 << 20, 4096))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> padrao.comTamanhoMaximoRegistro(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pequeno demais");
    assertThatThrownBy(() -> padrao.comBytesMaximosPorSegmento(1024))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("menor que o maior registro");
    assertThatThrownBy(() -> padrao.comIntervaloDoIndiceEmBytes(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intervalo do indice");
  }

  @Test
  void configuracaoPadraoDeveTrazerOsTetosDocumentados() {
    ConfiguracaoLog padrao = ConfiguracaoLog.padrao(relogioFixo());

    assertThat(padrao.tamanhoMaximoRegistro())
        .isEqualTo(ConfiguracaoLog.TAMANHO_MAXIMO_REGISTRO_PADRAO);
    assertThat(padrao.bytesMaximosPorSegmento())
        .isEqualTo(ConfiguracaoLog.BYTES_MAXIMOS_POR_SEGMENTO_PADRAO);
    assertThat(padrao.intervaloDoIndiceEmBytes())
        .isEqualTo(ConfiguracaoLog.INTERVALO_DO_INDICE_EM_BYTES_PADRAO);
    assertThat(padrao.comTamanhoMaximoRegistro(512).tamanhoMaximoRegistro()).isEqualTo(512);
    assertThat(padrao.comTamanhoMaximoRegistro(512).comBytesMaximosPorSegmento(4096)
            .bytesMaximosPorSegmento())
        .isEqualTo(4096);
    assertThat(padrao.comIntervaloDoIndiceEmBytes(64).intervaloDoIndiceEmBytes()).isEqualTo(64);
  }
}
