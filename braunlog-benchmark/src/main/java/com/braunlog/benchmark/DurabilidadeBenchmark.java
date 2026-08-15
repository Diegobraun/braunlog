package com.braunlog.benchmark;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import com.braunlog.Log;
import com.braunlog.ModoDurabilidade;
import com.braunlog.Offset;
import com.braunlog.Registro;

/**
 * Custo de um append em cada modo de durabilidade. A diferenca entre os modos e o custo do
 * {@code fsync}, e ele nao e uma constante do braunlog: e uma constante do dispositivo.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class DurabilidadeBenchmark {

  @Param({"nenhum", "por-intervalo-200ms", "a-cada-append"})
  public String modo;

  private DiretorioTemporario diretorio;
  private Log log;
  private Registro registro;

  @Setup(Level.Iteration)
  public void preparar() throws IOException {
    diretorio = DiretorioTemporario.criar("durabilidade");
    log = Log.abrir(diretorio.caminho(), Referencia.configuracao().comModoDurabilidade(modoEscolhido()));
    registro = Registro.de("chave-de-referencia".getBytes(), new byte[Referencia.BYTES_DO_VALOR]);
  }

  @TearDown(Level.Iteration)
  public void encerrar() throws IOException {
    log.close();
    diretorio.apagar();
  }

  @Benchmark
  public Offset anexar() {
    return log.anexar(registro);
  }

  private ModoDurabilidade modoEscolhido() {
    return switch (modo) {
      case "a-cada-append" -> ModoDurabilidade.A_CADA_APPEND;
      case "por-intervalo-200ms" -> ModoDurabilidade.porIntervalo(Duration.ofMillis(200));
      default -> ModoDurabilidade.NENHUM;
    };
  }
}
