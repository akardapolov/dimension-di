package ru.dimension.di.beans;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.UUID;

@Named
@Singleton
public class SingletonBean {
  public final UUID id = UUID.randomUUID();

  @Inject
  public SingletonBean() {}
}