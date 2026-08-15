package com.braunlog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Relogio controlado pelo teste: nenhum teste do braunlog depende do relogio da maquina. */
public final class RelogioAjustavel extends Clock {

  private final ZoneId fuso;
  private Instant instante;

  public RelogioAjustavel(Instant inicio) {
    this(inicio, ZoneOffset.UTC);
  }

  private RelogioAjustavel(Instant inicio, ZoneId fuso) {
    this.instante = inicio;
    this.fuso = fuso;
  }

  public static RelogioAjustavel em(long epochMillis) {
    return new RelogioAjustavel(Instant.ofEpochMilli(epochMillis));
  }

  public void avancar(Duration duracao) {
    instante = instante.plus(duracao);
  }

  public void voltar(Duration duracao) {
    instante = instante.minus(duracao);
  }

  @Override
  public Instant instant() {
    return instante;
  }

  @Override
  public ZoneId getZone() {
    return fuso;
  }

  @Override
  public Clock withZone(ZoneId outroFuso) {
    return new RelogioAjustavel(instante, outroFuso);
  }
}
