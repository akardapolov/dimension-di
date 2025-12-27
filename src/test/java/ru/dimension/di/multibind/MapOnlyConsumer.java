package ru.dimension.di.multibind;

import jakarta.inject.Inject;
import java.util.Map;

public class MapOnlyConsumer {
  public final Map<String, GreetingProvider> map;

  @Inject
  public MapOnlyConsumer(Map<String, GreetingProvider> map) {
    this.map = map;
  }
}