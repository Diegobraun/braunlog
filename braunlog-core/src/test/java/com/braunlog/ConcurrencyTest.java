package com.braunlog;

import static com.braunlog.Suporte.bytes;
import static com.braunlog.Suporte.configuracaoPadrao;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.braunlog.formato.FormatoRegistro;

/**
 * Um writer, varios leitores. Nenhum leitor pode ver registro pela metade nem lacuna no offset — e
 * a unica coisa que impede isso e o limite legivel, que so avanca depois de o registro inteiro estar
 * escrito.
 */
class ConcurrencyTest {

  private static final int REGISTROS = 3_000;
  private static final int LEITORES = 4;
  private static final int SEGUNDOS_DE_PACIENCIA = 60;

  @TempDir Path diretorio;

  @Test
  void leitoresConcorrentesNuncaDevemVerRegistroIncompletoNemLacuna() throws Exception {
    executarComEscritaConcorrente(configuracaoPadrao());
  }

  @Test
  void leitoresConcorrentesDevemAtravessarARolagemDeSegmentoSemPerderNada() throws Exception {
    int tamanhoDoRegistro = FormatoRegistro.tamanhoCodificado(registroDe(0));
    ConfiguracaoLog configuracao =
        configuracaoPadrao()
            .comTamanhoMaximoRegistro(tamanhoDoRegistro)
            .comBytesMaximosPorSegmento(tamanhoDoRegistro * 50L);

    int segmentos = executarComEscritaConcorrente(configuracao);

    assertThat(segmentos).isGreaterThan(10);
  }

  private int executarComEscritaConcorrente(ConfiguracaoLog configuracao) throws Exception {
    // given
    CountDownLatch largada = new CountDownLatch(1);
    AtomicBoolean escritaEncerrada = new AtomicBoolean();
    ExecutorService executor = Executors.newFixedThreadPool(LEITORES + 1);

    try (Log log = Log.abrir(diretorio, configuracao)) {
      Future<Integer> escrita =
          executor.submit(
              () -> {
                largada.await();
                try {
                  for (int i = 0; i < REGISTROS; i++) {
                    log.anexar(registroDe(i));
                  }
                } finally {
                  escritaEncerrada.set(true);
                }
                return REGISTROS;
              });

      List<Future<Integer>> leituras = new ArrayList<>();
      for (int leitor = 0; leitor < LEITORES; leitor++) {
        leituras.add(executor.submit(() -> {
          largada.await();
          int vistos = 0;
          while (vistos < REGISTROS && !escritaEncerrada.get()) {
            vistos = lerConferindo(log);
          }
          return lerConferindo(log);
        }));
      }

      // when
      largada.countDown();

      // then
      assertThat(escrita.get(SEGUNDOS_DE_PACIENCIA, TimeUnit.SECONDS)).isEqualTo(REGISTROS);
      for (Future<Integer> leitura : leituras) {
        assertThat(leitura.get(SEGUNDOS_DE_PACIENCIA, TimeUnit.SECONDS)).isEqualTo(REGISTROS);
      }
      return log.quantidadeDeSegmentos();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Le tudo do inicio conferindo, registro a registro, que o offset e o esperado e que o conteudo e
   * exatamente o que aquele offset deveria ter. Um registro lido pela metade ou um offset pulado
   * quebram aqui.
   */
  private static int lerConferindo(Log log) {
    int esperado = 0;
    try (Leitor leitor = log.lerDe(Offset.ZERO)) {
      while (leitor.hasNext()) {
        RegistroLido lido = leitor.next();
        if (lido.offset().valor() != esperado) {
          throw new AssertionError("esperava offset " + esperado + " e veio " + lido.offset());
        }
        Registro esperadoNoOffset = registroDe(esperado);
        if (!lido.registro().equals(esperadoNoOffset)) {
          throw new AssertionError("conteudo diferente no offset " + esperado);
        }
        esperado++;
      }
    }
    return esperado;
  }

  private static Registro registroDe(int indice) {
    return Registro.de(bytes("chave-%05d".formatted(indice)), bytes("valor-%05d".formatted(indice)));
  }
}
