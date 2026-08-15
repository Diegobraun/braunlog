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
import com.braunlog.ModoDurabilidade;
import com.braunlog.Registro;

/**
 * Custo de um lote de N registros com um unico {@code fsync} no fim. Mostra como o custo do fsync se
 * dilui: e o mesmo raciocinio do {@code linger.ms} do Kafka, sem nenhuma magia.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class LoteBenchmark {

  @Param({"1", "10", "100", "1000"})
  public int registrosPorLote;

  private DiretorioTemporario diretorio;
  private Log log;
  private Registro registro;

  @Setup(Level.Iteration)
  public void preparar() throws IOException {
    diretorio = DiretorioTemporario.criar("lote");
    log =
        Log.abrir(
            diretorio.caminho(),
            Referencia.configuracao().comModoDurabilidade(ModoDurabilidade.NENHUM));
    registro = Registro.de("chave-de-referencia".getBytes(), new byte[Referencia.BYTES_DO_VALOR]);
  }

  @TearDown(Level.Iteration)
  public void encerrar() throws IOException {
    log.close();
    diretorio.apagar();
  }

  @Benchmark
  public void anexarLoteESincronizar() {
    for (int i = 0; i < registrosPorLote; i++) {
      log.anexar(registro);
    }
    log.forcarSincronizacao();
  }
}
