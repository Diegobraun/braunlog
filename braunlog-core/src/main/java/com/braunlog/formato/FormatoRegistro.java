package com.braunlog.formato;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import com.braunlog.Offset;
import com.braunlog.Registro;
import com.braunlog.RegistroLido;

/**
 * Codificacao e decodificacao de um registro. Especificacao normativa em {@code docs/formato.md};
 * qualquer divergencia entre esta classe e aquele documento e bug desta classe.
 */
public final class FormatoRegistro {

  public static final byte VERSAO_ATUAL = 1;

  static final int DESLOCAMENTO_TAMANHO = 0;
  static final int DESLOCAMENTO_CRC = 4;
  static final int DESLOCAMENTO_VERSAO = 8;
  static final int DESLOCAMENTO_ATRIBUTOS = 9;
  static final int DESLOCAMENTO_OFFSET_RELATIVO = 10;
  static final int DESLOCAMENTO_TIMESTAMP = 14;
  static final int DESLOCAMENTO_TAMANHO_CHAVE = 22;
  static final int DESLOCAMENTO_CHAVE = 26;

  /** O CRC cobre tudo a partir de {@code versaoFormato}: nem o tamanho, nem ele proprio. */
  static final int INICIO_AREA_COBERTA_PELO_CRC = DESLOCAMENTO_VERSAO;

  public static final int BYTES_TAMANHO = 4;
  public static final int BYTES_CABECALHO_FIXO = DESLOCAMENTO_CHAVE;
  public static final int BYTES_MINIMOS_REGISTRO = BYTES_CABECALHO_FIXO + BYTES_TAMANHO;
  public static final int VALOR_MINIMO_CAMPO_TAMANHO = BYTES_MINIMOS_REGISTRO - BYTES_TAMANHO;

  static final byte ATRIBUTOS_RESERVADOS = 0;
  static final int BLOCO_AUSENTE = -1;

  private FormatoRegistro() {}

  public static int tamanhoCodificado(Registro registro) {
    return BYTES_MINIMOS_REGISTRO + bytesDe(registro.chave()) + bytesDe(registro.valor());
  }

  /** Escreve o registro no buffer a partir da posicao corrente, deixando-a no fim do registro. */
  public static void codificar(
      ByteBuffer destino, Registro registro, int offsetRelativo, long timestamp) {
    if (offsetRelativo < 0) {
      throw new IllegalArgumentException("offset relativo negativo: " + offsetRelativo);
    }
    int inicio = destino.position();
    int tamanhoTotal = tamanhoCodificado(registro);

    destino.putInt(tamanhoTotal - BYTES_TAMANHO);
    destino.putInt(0);
    destino.put(VERSAO_ATUAL);
    destino.put(ATRIBUTOS_RESERVADOS);
    destino.putInt(offsetRelativo);
    destino.putLong(timestamp);
    escreverBloco(destino, registro.chave());
    escreverBloco(destino, registro.valor());

    destino.putInt(inicio + DESLOCAMENTO_CRC, calcularCrc(destino, inicio, tamanhoTotal));
  }

  /**
   * Decodifica um registro completo. O buffer precisa conter exatamente um registro, do campo
   * {@code tamanho} ate o ultimo byte do valor.
   */
  public static ResultadoDecodificacao decodificar(ByteBuffer registro, long offsetBase) {
    int tamanhoTotal = registro.limit() - registro.position();
    int inicio = registro.position();

    int declarado = registro.getInt(inicio + DESLOCAMENTO_TAMANHO);
    if (declarado + BYTES_TAMANHO != tamanhoTotal) {
      return corrompido("campo tamanho " + declarado + " nao casa com " + tamanhoTotal + " bytes");
    }
    int crcGravado = registro.getInt(inicio + DESLOCAMENTO_CRC);
    int crcCalculado = calcularCrc(registro, inicio, tamanhoTotal);
    if (crcGravado != crcCalculado) {
      return corrompido("crc gravado " + crcGravado + " diferente do calculado " + crcCalculado);
    }
    byte versao = registro.get(inicio + DESLOCAMENTO_VERSAO);
    if (versao != VERSAO_ATUAL) {
      return corrompido("versao de formato desconhecida: " + versao);
    }
    byte atributos = registro.get(inicio + DESLOCAMENTO_ATRIBUTOS);
    if (atributos != ATRIBUTOS_RESERVADOS) {
      return corrompido("atributos desconhecidos: " + atributos);
    }
    int offsetRelativo = registro.getInt(inicio + DESLOCAMENTO_OFFSET_RELATIVO);
    if (offsetRelativo < 0) {
      return corrompido("offset relativo negativo: " + offsetRelativo);
    }

    int tamanhoChave = registro.getInt(inicio + DESLOCAMENTO_TAMANHO_CHAVE);
    int maximoChave = tamanhoTotal - DESLOCAMENTO_CHAVE - BYTES_TAMANHO;
    if (tamanhoChave < BLOCO_AUSENTE || tamanhoChave > maximoChave) {
      return corrompido("tamanho de chave invalido: " + tamanhoChave);
    }
    int posicaoTamanhoValor = DESLOCAMENTO_CHAVE + Math.max(0, tamanhoChave);
    int tamanhoValor = registro.getInt(inicio + posicaoTamanhoValor);
    int maximoValor = tamanhoTotal - posicaoTamanhoValor - BYTES_TAMANHO;
    if (tamanhoValor < BLOCO_AUSENTE || tamanhoValor > maximoValor) {
      return corrompido("tamanho de valor invalido: " + tamanhoValor);
    }
    int fim = posicaoTamanhoValor + BYTES_TAMANHO + Math.max(0, tamanhoValor);
    if (fim != tamanhoTotal) {
      return corrompido("registro termina em " + fim + " e nao em " + tamanhoTotal);
    }
    if (tamanhoChave == BLOCO_AUSENTE && tamanhoValor == BLOCO_AUSENTE) {
      return corrompido("registro sem chave e sem valor");
    }

    byte[] chave = lerBloco(registro, inicio + DESLOCAMENTO_CHAVE, tamanhoChave);
    byte[] valor = lerBloco(registro, inicio + posicaoTamanhoValor + BYTES_TAMANHO, tamanhoValor);
    RegistroLido lido =
        new RegistroLido(
            Offset.de(offsetBase + offsetRelativo),
            registro.getLong(inicio + DESLOCAMENTO_TIMESTAMP),
            chave,
            valor);
    return new ResultadoDecodificacao.Sucesso(lido, tamanhoTotal);
  }

  static int calcularCrc(ByteBuffer buffer, int inicio, int tamanhoTotal) {
    ByteBuffer area = buffer.duplicate();
    area.limit(inicio + tamanhoTotal).position(inicio + INICIO_AREA_COBERTA_PELO_CRC);
    CRC32C crc = new CRC32C();
    crc.update(area);
    return (int) crc.getValue();
  }

  private static ResultadoDecodificacao corrompido(String motivo) {
    return new ResultadoDecodificacao.Corrompido(motivo);
  }

  private static byte[] lerBloco(ByteBuffer buffer, int posicao, int tamanho) {
    if (tamanho == BLOCO_AUSENTE) {
      return null;
    }
    byte[] bloco = new byte[tamanho];
    buffer.duplicate().position(posicao).get(bloco);
    return bloco;
  }

  private static void escreverBloco(ByteBuffer destino, byte[] bloco) {
    if (bloco == null) {
      destino.putInt(BLOCO_AUSENTE);
      return;
    }
    destino.putInt(bloco.length);
    destino.put(bloco);
  }

  private static int bytesDe(byte[] bloco) {
    return bloco == null ? 0 : bloco.length;
  }
}
