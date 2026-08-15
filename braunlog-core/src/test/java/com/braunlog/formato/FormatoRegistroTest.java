package com.braunlog.formato;

import static com.braunlog.Suporte.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.braunlog.Registro;
import com.braunlog.RegistroLido;

class FormatoRegistroTest {

  private static final long OFFSET_BASE = 100;
  private static final int OFFSET_RELATIVO = 7;
  private static final long TIMESTAMP = 1_700_000_000_123L;

  @Test
  void deveFazerRoundTripDeChaveEValor() {
    // given
    Registro registro = Registro.de(bytes("usuario-1"), bytes("ativo"));

    // when
    RegistroLido lido = decodificarComSucesso(codificar(registro));

    // then
    assertThat(lido.chave()).isEqualTo(bytes("usuario-1"));
    assertThat(lido.valor()).isEqualTo(bytes("ativo"));
    assertThat(lido.timestamp()).isEqualTo(TIMESTAMP);
    assertThat(lido.offset().valor()).isEqualTo(OFFSET_BASE + OFFSET_RELATIVO);
    assertThat(lido.ehTombstone()).isFalse();
    assertThat(lido.temChave()).isTrue();
    assertThat(lido.registro()).isEqualTo(registro);
  }

  @Test
  void deveFazerRoundTripDeTombstone() {
    // given
    Registro registro = Registro.tombstone(bytes("usuario-1"));

    // when
    RegistroLido lido = decodificarComSucesso(codificar(registro));

    // then
    assertThat(lido.ehTombstone()).isTrue();
    assertThat(lido.valor()).isNull();
    assertThat(lido.chave()).isEqualTo(bytes("usuario-1"));
  }

  @Test
  void deveFazerRoundTripDeRegistroSemChave() {
    // given
    Registro registro = Registro.semChave(bytes("evento"));

    // when
    RegistroLido lido = decodificarComSucesso(codificar(registro));

    // then
    assertThat(lido.temChave()).isFalse();
    assertThat(lido.chave()).isNull();
    assertThat(lido.valor()).isEqualTo(bytes("evento"));
  }

  @Test
  void deveFazerRoundTripDeChaveEValorVazios() {
    // given
    Registro registro = Registro.de(new byte[0], new byte[0]);

    // when
    RegistroLido lido = decodificarComSucesso(codificar(registro));

    // then
    assertThat(lido.chave()).isEmpty();
    assertThat(lido.valor()).isEmpty();
  }

  @Test
  void deveCalcularTamanhoCodificadoComoCabecalhoMaisBlocos() {
    // given
    Registro registro = Registro.de(new byte[3], new byte[5]);

    // when
    int tamanho = FormatoRegistro.tamanhoCodificado(registro);

    // then
    assertThat(tamanho).isEqualTo(FormatoRegistro.BYTES_MINIMOS_REGISTRO + 3 + 5);
    assertThat(codificar(registro).limit()).isEqualTo(tamanho);
  }

  @Test
  void deveRecusarCodificarComOffsetRelativoNegativo() {
    // given
    ByteBuffer destino = ByteBuffer.allocate(64);

    // when / then
    assertThatThrownBy(() -> FormatoRegistro.codificar(destino, registroQualquer(), -1, TIMESTAMP))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveDetectarCrcDivergenteQuandoUmByteDoValorMuda() {
    // given
    ByteBuffer registro = codificar(registroQualquer());
    int ultimoByte = registro.limit() - 1;
    registro.put(ultimoByte, (byte) (registro.get(ultimoByte) ^ 0xFF));

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(resultado)
        .isInstanceOfSatisfying(
            ResultadoDecodificacao.Corrompido.class,
            corrompido -> assertThat(corrompido.motivo()).contains("crc"));
  }

  @Test
  void deveDetectarCampoTamanhoIncoerenteComOsBytesDisponiveis() {
    // given
    ByteBuffer registro = codificar(registroQualquer());
    registro.putInt(FormatoRegistro.DESLOCAMENTO_TAMANHO, registro.limit());

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("campo tamanho");
  }

  @Test
  void deveDetectarVersaoDeFormatoDesconhecida() {
    // given
    ByteBuffer registro = comCampoAlterado(FormatoRegistro.DESLOCAMENTO_VERSAO, (byte) 2);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("versao");
  }

  @Test
  void deveDetectarAtributoDesconhecidoParaNaoLerFormatoFuturoPelaMetade() {
    // given
    ByteBuffer registro = comCampoAlterado(FormatoRegistro.DESLOCAMENTO_ATRIBUTOS, (byte) 1);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("atributos");
  }

  @Test
  void deveDetectarOffsetRelativoNegativo() {
    // given
    ByteBuffer registro = comInteiroAlterado(FormatoRegistro.DESLOCAMENTO_OFFSET_RELATIVO, -1);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("offset relativo");
  }

  @Test
  void deveDetectarTamanhoDeChaveForaDaFaixa() {
    // given
    ByteBuffer registro = comInteiroAlterado(FormatoRegistro.DESLOCAMENTO_TAMANHO_CHAVE, -2);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("tamanho de chave");
  }

  @Test
  void deveDetectarChaveQueNaoCabeNoRegistro() {
    // given
    ByteBuffer registro = codificar(registroQualquer());
    ByteBuffer alterado = comInteiroAlterado(FormatoRegistro.DESLOCAMENTO_TAMANHO_CHAVE,
        registro.limit() - 1);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(alterado, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("tamanho de chave");
  }

  @Test
  void deveDetectarTamanhoDeValorForaDaFaixa() {
    // given
    int deslocamentoTamanhoValor =
        FormatoRegistro.DESLOCAMENTO_CHAVE + bytes("usuario-1").length;
    ByteBuffer registro = comInteiroAlterado(deslocamentoTamanhoValor, -2);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("tamanho de valor");
  }

  @Test
  void deveDetectarValorQueNaoTerminaNoFimDoRegistro() {
    // given
    int deslocamentoTamanhoValor =
        FormatoRegistro.DESLOCAMENTO_CHAVE + bytes("usuario-1").length;
    ByteBuffer registro = comInteiroAlterado(deslocamentoTamanhoValor, 0);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("termina em");
  }

  @Test
  void deveDetectarRegistroSemChaveESemValor() {
    // given
    ByteBuffer semChave = codificar(Registro.semChave(new byte[0]));
    ByteBuffer registro =
        regravarCrc(
            semChave, FormatoRegistro.DESLOCAMENTO_CHAVE, FormatoRegistro.BLOCO_AUSENTE);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);

    // then
    assertThat(motivoDeCorrupcao(resultado)).contains("sem chave e sem valor");
  }

  @Test
  void deveAceitarChaveQueOcupaTodoOEspacoDisponivelDoRegistro() {
    // given
    Registro tombstone = Registro.tombstone(bytes("chave-que-ocupa-tudo"));

    // when
    RegistroLido lido = decodificarComSucesso(codificar(tombstone));

    // then
    assertThat(lido.chave()).isEqualTo(bytes("chave-que-ocupa-tudo"));
    assertThat(lido.ehTombstone()).isTrue();
  }

  @Test
  void deveDecodificarRegistroQueNaoComecaNaPosicaoZeroDoBuffer() {
    // given
    ByteBuffer registro = codificar(registroQualquer());
    ByteBuffer comPrefixo = ByteBuffer.allocate(registro.limit() + 3);
    comPrefixo.position(3).put(registro).position(3);

    // when
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(comPrefixo, OFFSET_BASE);

    // then
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Sucesso.class);
  }

  private static Registro registroQualquer() {
    return Registro.de(bytes("usuario-1"), bytes("ativo"));
  }

  private static ByteBuffer codificar(Registro registro) {
    ByteBuffer destino = ByteBuffer.allocate(FormatoRegistro.tamanhoCodificado(registro));
    FormatoRegistro.codificar(destino, registro, OFFSET_RELATIVO, TIMESTAMP);
    return destino.flip();
  }

  private static ByteBuffer comCampoAlterado(int deslocamento, byte valor) {
    ByteBuffer registro = codificar(registroQualquer());
    registro.put(deslocamento, valor);
    return regravarCrcDoBufferInteiro(registro);
  }

  private static ByteBuffer comInteiroAlterado(int deslocamento, int valor) {
    return regravarCrc(codificar(registroQualquer()), deslocamento, valor);
  }

  private static ByteBuffer regravarCrc(ByteBuffer registro, int deslocamento, int valor) {
    registro.putInt(deslocamento, valor);
    return regravarCrcDoBufferInteiro(registro);
  }

  private static ByteBuffer regravarCrcDoBufferInteiro(ByteBuffer registro) {
    int crc = FormatoRegistro.calcularCrc(registro, 0, registro.limit());
    registro.putInt(FormatoRegistro.DESLOCAMENTO_CRC, crc);
    return registro;
  }

  private static RegistroLido decodificarComSucesso(ByteBuffer registro) {
    ResultadoDecodificacao resultado = FormatoRegistro.decodificar(registro, OFFSET_BASE);
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Sucesso.class);
    ResultadoDecodificacao.Sucesso sucesso = (ResultadoDecodificacao.Sucesso) resultado;
    assertThat(sucesso.bytesConsumidos()).isEqualTo(registro.limit());
    return sucesso.registro();
  }

  private static String motivoDeCorrupcao(ResultadoDecodificacao resultado) {
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Corrompido.class);
    return ((ResultadoDecodificacao.Corrompido) resultado).motivo();
  }
}
