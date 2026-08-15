package com.braunlog;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Quando um segmento pode ser descartado. A decisao e sempre sobre o segmento inteiro: o log e
 * append-only, entao apagar um registro do meio de um arquivo nao existe como operacao.
 */
public sealed interface PoliticaRetencao {

  PoliticaRetencao NENHUMA = new Nenhuma();

  /**
   * @param bytesDoLog soma dos bytes de todos os segmentos, incluindo o ativo
   * @param ultimoTimestampDoSegmento timestamp do registro mais recente do segmento candidato
   * @param agora epoch millis de referencia, vindo do relogio injetado
   */
  boolean deveDescartar(long bytesDoLog, long ultimoTimestampDoSegmento, long agora);

  record Nenhuma() implements PoliticaRetencao {
    @Override
    public boolean deveDescartar(long bytesDoLog, long ultimoTimestampDoSegmento, long agora) {
      return false;
    }
  }

  record PorTamanhoTotal(long bytesMaximos) implements PoliticaRetencao {
    public PorTamanhoTotal {
      if (bytesMaximos <= 0) {
        throw new IllegalArgumentException("bytes maximos precisa ser positivo: " + bytesMaximos);
      }
    }

    @Override
    public boolean deveDescartar(long bytesDoLog, long ultimoTimestampDoSegmento, long agora) {
      return bytesDoLog > bytesMaximos;
    }
  }

  record PorIdade(Duration idadeMaxima) implements PoliticaRetencao {
    public PorIdade {
      Objects.requireNonNull(idadeMaxima, "idadeMaxima");
      if (idadeMaxima.isNegative() || idadeMaxima.isZero()) {
        throw new IllegalArgumentException("idade maxima precisa ser positiva: " + idadeMaxima);
      }
    }

    @Override
    public boolean deveDescartar(long bytesDoLog, long ultimoTimestampDoSegmento, long agora) {
      return agora - ultimoTimestampDoSegmento > idadeMaxima.toMillis();
    }
  }

  /** Descarta quando qualquer uma das politicas mandar descartar. */
  record Combinada(List<PoliticaRetencao> politicas) implements PoliticaRetencao {
    public Combinada {
      politicas = List.copyOf(politicas);
      if (politicas.isEmpty()) {
        throw new IllegalArgumentException("combinada precisa de ao menos uma politica");
      }
    }

    @Override
    public boolean deveDescartar(long bytesDoLog, long ultimoTimestampDoSegmento, long agora) {
      return politicas.stream()
          .anyMatch(politica -> politica.deveDescartar(bytesDoLog, ultimoTimestampDoSegmento, agora));
    }
  }

  static PoliticaRetencao porTamanhoTotal(long bytesMaximos) {
    return new PorTamanhoTotal(bytesMaximos);
  }

  static PoliticaRetencao porIdade(Duration idadeMaxima) {
    return new PorIdade(idadeMaxima);
  }

  static PoliticaRetencao combinada(PoliticaRetencao... politicas) {
    return new Combinada(List.of(politicas));
  }
}
