package com.braunlog.benchmark;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import com.braunlog.ConfiguracaoLog;
import com.braunlog.Leitor;
import com.braunlog.Log;
import com.braunlog.Offset;
import com.braunlog.Registro;

/**
 * Throughput de leitura sequencial e custo de posicionar em um offset aleatorio, variando o
 * intervalo do indice esparso.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class LeituraBenchmark {

  @Param({"4096", "65536"})
  public int intervaloDoIndiceEmBytes;

  private DiretorioTemporario diretorio;
  private Log log;

  @Setup(Level.Trial)
  public void preparar() throws IOException {
    diretorio = DiretorioTemporario.criar("leitura");
    ConfiguracaoLog configuracao =
        Referencia.configuracao().comIntervaloDoIndiceEmBytes(intervaloDoIndiceEmBytes);
    log = Log.abrir(diretorio.caminho(), configuracao);
    byte[] valor = new byte[Referencia.BYTES_DO_VALOR_NA_LEITURA];
    for (int i = 0; i < Referencia.REGISTROS_PARA_LEITURA; i++) {
      log.anexar(Registro.de(("chave-" + i).getBytes(), valor));
    }
  }

  @TearDown(Level.Trial)
  public void encerrar() throws IOException {
    log.close();
    diretorio.apagar();
  }

  @Benchmark
  @OperationsPerInvocation(Referencia.REGISTROS_PARA_LEITURA)
  public long lerSequencialmente() {
    long lidos = 0;
    try (Leitor leitor = log.lerDe(Offset.ZERO)) {
      while (leitor.hasNext()) {
        leitor.next();
        lidos++;
      }
    }
    return lidos;
  }

  @Benchmark
  public Object lerUmRegistroEmOffsetAleatorio() {
    long alvo = ThreadLocalRandom.current().nextInt(Referencia.REGISTROS_PARA_LEITURA);
    try (Leitor leitor = log.lerDe(Offset.de(alvo))) {
      return leitor.next();
    }
  }
}
