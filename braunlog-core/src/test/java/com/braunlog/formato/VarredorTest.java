package com.braunlog.formato;

import static com.braunlog.Suporte.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.ConfiguracaoLog;
import com.braunlog.Registro;

class VarredorTest {

  private static final int TAMANHO_MAXIMO = ConfiguracaoLog.TAMANHO_MAXIMO_REGISTRO_PADRAO;
  private static final long OFFSET_BASE = 0;

  @TempDir Path diretorio;

  @Test
  void deveDevolverFimQuandoNaoHaMaisBytes() throws IOException {
    // given
    byte[] arquivo = umRegistro();

    // when
    ResultadoDecodificacao resultado = ler(arquivo, arquivo.length, arquivo.length);

    // then
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Fim.class);
  }

  @Test
  void deveDevolverParcialQuandoNemOCampoTamanhoEstaCompleto() throws IOException {
    // given
    byte[] arquivo = umRegistro();

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, 3);

    // then
    assertThat(resultado)
        .isEqualTo(new ResultadoDecodificacao.Parcial(3));
  }

  @Test
  void deveDevolverParcialQuandoORegistroFoiCortadoNoMeio() throws IOException {
    // given
    byte[] arquivo = umRegistro();
    int corte = arquivo.length - 1;

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, corte);

    // then
    assertThat(resultado).isEqualTo(new ResultadoDecodificacao.Parcial(corte));
  }

  @Test
  void deveDevolverCorrompidoQuandoOCampoTamanhoEMenorQueOMinimo() throws IOException {
    // given
    byte[] arquivo = umRegistro();
    ByteBuffer.wrap(arquivo).putInt(0, FormatoRegistro.VALOR_MINIMO_CAMPO_TAMANHO - 1);

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, arquivo.length);

    // then
    assertThat(motivo(resultado)).contains("fora da faixa");
  }

  @Test
  void deveDevolverCorrompidoQuandoOCampoTamanhoPassaDoMaximoConfigurado() throws IOException {
    // given
    byte[] arquivo = umRegistro();
    ByteBuffer.wrap(arquivo).putInt(0, TAMANHO_MAXIMO);

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, arquivo.length);

    // then
    assertThat(motivo(resultado)).contains("fora da faixa");
  }

  @Test
  void deveLerRegistroCompletoEInformarQuantosBytesConsumiu() throws IOException {
    // given
    byte[] arquivo = umRegistro();

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, arquivo.length);

    // then
    assertThat(resultado)
        .isInstanceOfSatisfying(
            ResultadoDecodificacao.Sucesso.class,
            sucesso -> {
              assertThat(sucesso.bytesConsumidos()).isEqualTo(arquivo.length);
              assertThat(sucesso.registro().chave()).isEqualTo(bytes("chave"));
            });
  }

  @Test
  void deveAceitarRegistroNoTamanhoMinimoPossivel() throws IOException {
    // given
    byte[] arquivo = codificar(Registro.tombstone(new byte[0]));
    assertThat(arquivo).hasSize(FormatoRegistro.BYTES_MINIMOS_REGISTRO);

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, arquivo.length, TAMANHO_MAXIMO);

    // then
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Sucesso.class);
  }

  @Test
  void deveAceitarRegistroExatamenteNoTamanhoMaximoConfigurado() throws IOException {
    // given
    byte[] arquivo = umRegistro();

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, arquivo.length, arquivo.length);

    // then
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Sucesso.class);
  }

  @Test
  void deveClassificarComoCorrompidoQuandoSoRestaOCampoTamanhoEEleEInvalido() throws IOException {
    // given
    byte[] arquivo = new byte[FormatoRegistro.BYTES_TAMANHO];

    // when
    ResultadoDecodificacao resultado = ler(arquivo, 0, arquivo.length);

    // then
    assertThat(motivo(resultado)).contains("fora da faixa");
  }

  @Test
  void deveFalharQuandoOLimiteInformadoPassaDoFimDoArquivo() throws IOException {
    // given
    byte[] completo = umRegistro();
    byte[] arquivo = Arrays.copyOf(completo, completo.length - 2);

    // when / then
    assertThatThrownBy(() -> ler(arquivo, 0, completo.length))
        .isInstanceOf(EOFException.class)
        .hasMessageContaining("terminou antes");
  }

  @Test
  void deveRespeitarLimiteMenorQueOTamanhoDoArquivo() throws IOException {
    // given
    byte[] primeiro = umRegistro();
    byte[] arquivo = concatenar(primeiro, umRegistro());

    // when
    ResultadoDecodificacao resultado = ler(arquivo, primeiro.length, primeiro.length);

    // then
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Fim.class);
  }

  private ResultadoDecodificacao ler(byte[] conteudo, long posicao, long limite)
      throws IOException {
    return ler(conteudo, posicao, limite, TAMANHO_MAXIMO);
  }

  private ResultadoDecodificacao ler(
      byte[] conteudo, long posicao, long limite, int tamanhoMaximoRegistro) throws IOException {
    Path arquivo = diretorio.resolve("segmento.log");
    Files.write(arquivo, conteudo);
    try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.READ)) {
      return new Varredor(canal, tamanhoMaximoRegistro).ler(posicao, limite, OFFSET_BASE);
    }
  }

  private static byte[] umRegistro() {
    return codificar(Registro.de(bytes("chave"), bytes("valor")));
  }

  private static byte[] codificar(Registro registro) {
    ByteBuffer buffer = ByteBuffer.allocate(FormatoRegistro.tamanhoCodificado(registro));
    FormatoRegistro.codificar(buffer, registro, 0, 1L);
    return buffer.array();
  }

  private static byte[] concatenar(byte[] primeiro, byte[] segundo) {
    byte[] juntos = new byte[primeiro.length + segundo.length];
    System.arraycopy(primeiro, 0, juntos, 0, primeiro.length);
    System.arraycopy(segundo, 0, juntos, primeiro.length, segundo.length);
    return juntos;
  }

  private static String motivo(ResultadoDecodificacao resultado) {
    assertThat(resultado).isInstanceOf(ResultadoDecodificacao.Corrompido.class);
    return ((ResultadoDecodificacao.Corrompido) resultado).motivo();
  }
}
