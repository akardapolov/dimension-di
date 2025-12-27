package ru.dimension.di.multibind;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QualifiedGreetingConsumer {

  private final List<GreetingProvider> onlyHelloList;
  private final Set<GreetingProvider> onlyHelloSet;
  private final Map<String, GreetingProvider> onlyHelloMap;

  @Inject
  public QualifiedGreetingConsumer(
      @Named("HelloGreetingProvider") List<GreetingProvider> onlyHelloList,
      @Named("HelloGreetingProvider") Set<GreetingProvider> onlyHelloSet,
      @Named("HelloGreetingProvider") Map<String, GreetingProvider> onlyHelloMap
  ) {
    this.onlyHelloList = onlyHelloList;
    this.onlyHelloSet = onlyHelloSet;
    this.onlyHelloMap = onlyHelloMap;
  }

  public List<GreetingProvider> onlyHelloList() {
    return onlyHelloList;
  }

  public Set<GreetingProvider> onlyHelloSet() {
    return onlyHelloSet;
  }

  public Map<String, GreetingProvider> onlyHelloMap() {
    return onlyHelloMap;
  }
}