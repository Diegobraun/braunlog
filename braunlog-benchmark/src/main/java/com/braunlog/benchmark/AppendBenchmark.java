package com.braunlog.benchmark;

import java.io.IOException;
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
import com.braunlog.Offset;
import com.braunlog.Registro;

/** Throughput de append em funcao do tamanho do valor, sem fsync explicito. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class AppendBenchmark {

  @Param({"64", "512", "4096"})
  public int bytesDoValor;

  private DiretorioTemporario diretorio;
  private Log log;
  private Registro registro;

  @Setup(Level.Iteration)
  public void preparar() throws IOException {
    diretorio = DiretorioTemporario.criar("append");
    log = Log.abrir(diretorio.caminho(), Referencia.configuracao());
    registro = Registro.de("chave-de-referencia".getBytes(), new byte[bytesDoValor]);
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
}
