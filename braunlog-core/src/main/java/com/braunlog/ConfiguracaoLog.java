package com.braunlog;

import java.time.Clock;
import java.util.Objects;

import com.braunlog.formato.FormatoRegistro;

/**
 * Configuracao de um log. O {@link Clock} entra por parametro para que o timestamp gravado seja
 * controlavel em teste.
 *
 * @param relogio fonte do timestamp de cada registro
 * @param tamanhoMaximoRegistro teto de bytes de um registro codificado; tambem e o limite que
 *     permite classificar um campo {@code tamanho} absurdo como corrupcao em vez de alocar memoria
 *     arbitraria na leitura
 * @param bytesMaximosPorSegmento tamanho a partir do qual o segmento ativo e fechado e um novo e
 *     criado
 * @param intervaloDoIndiceEmBytes quantos bytes o segmento precisa crescer entre duas entradas do
 *     indice esparso
 * @param modoDurabilidade quando o log chama {@code fsync}
 */
public record ConfiguracaoLog(
    Clock relogio,
    int tamanhoMaximoRegistro,
    long bytesMaximosPorSegmento,
    int intervaloDoIndiceEmBytes,
    ModoDurabilidade modoDurabilidade) {

  public static final int TAMANHO_MAXIMO_REGISTRO_PADRAO = 1 << 20;
  public static final long BYTES_MAXIMOS_POR_SEGMENTO_PADRAO = 64L << 20;
  public static final int INTERVALO_DO_INDICE_EM_BYTES_PADRAO = 4 << 10;
  public static final ModoDurabilidade MODO_DURABILIDADE_PADRAO = ModoDurabilidade.NENHUM;

  public ConfiguracaoLog {
    Objects.requireNonNull(relogio, "relogio");
    if (tamanhoMaximoRegistro < FormatoRegistro.BYTES_MINIMOS_REGISTRO) {
      throw new IllegalArgumentException("tamanho maximo de registro pequeno demais");
    }
    if (bytesMaximosPorSegmento < tamanhoMaximoRegistro) {
      throw new IllegalArgumentException("segmento menor que o maior registro possivel");
    }
    if (intervaloDoIndiceEmBytes <= 0) {
      throw new IllegalArgumentException("intervalo do indice precisa ser positivo");
    }
    Objects.requireNonNull(modoDurabilidade, "modoDurabilidade");
  }

  public static ConfiguracaoLog padrao(Clock relogio) {
    return new ConfiguracaoLog(
        relogio,
        TAMANHO_MAXIMO_REGISTRO_PADRAO,
        BYTES_MAXIMOS_POR_SEGMENTO_PADRAO,
        INTERVALO_DO_INDICE_EM_BYTES_PADRAO,
        MODO_DURABILIDADE_PADRAO);
  }

  public ConfiguracaoLog comTamanhoMaximoRegistro(int tamanhoMaximoRegistro) {
    return new ConfiguracaoLog(
        relogio,
        tamanhoMaximoRegistro,
        bytesMaximosPorSegmento,
        intervaloDoIndiceEmBytes,
        modoDurabilidade);
  }

  public ConfiguracaoLog comBytesMaximosPorSegmento(long bytesMaximosPorSegmento) {
    return new ConfiguracaoLog(
        relogio,
        tamanhoMaximoRegistro,
        bytesMaximosPorSegmento,
        intervaloDoIndiceEmBytes,
        modoDurabilidade);
  }

  public ConfiguracaoLog comIntervaloDoIndiceEmBytes(int intervaloDoIndiceEmBytes) {
    return new ConfiguracaoLog(
        relogio,
        tamanhoMaximoRegistro,
        bytesMaximosPorSegmento,
        intervaloDoIndiceEmBytes,
        modoDurabilidade);
  }

  public ConfiguracaoLog comModoDurabilidade(ModoDurabilidade modoDurabilidade) {
    return new ConfiguracaoLog(
        relogio,
        tamanhoMaximoRegistro,
        bytesMaximosPorSegmento,
        intervaloDoIndiceEmBytes,
        modoDurabilidade);
  }
}
