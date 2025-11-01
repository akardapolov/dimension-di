package ru.dimension.di.beans;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.UUID;

@Named
public class PrototypeBean {
  public final UUID id = UUID.randomUUID();

  @Inject
  public PrototypeBean() {}
}