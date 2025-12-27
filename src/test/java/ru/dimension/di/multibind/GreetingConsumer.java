package ru.dimension.di.multibind;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GreetingConsumer {

  private final List<GreetingProvider> list;
  private final Set<GreetingProvider> set;
  private final Map<String, GreetingProvider> map;

  @Inject
  public GreetingConsumer(List<GreetingProvider> list,
                          Set<GreetingProvider> set,
                          Map<String, GreetingProvider> map) {
    this.list = list;
    this.set = set;
    this.map = map;
  }

  public List<GreetingProvider> list() {
    return list;
  }

  public Set<GreetingProvider> set() {
    return set;
  }

  public Map<String, GreetingProvider> map() {
    return map;
  }

  public Set<String> greetings() {
    return list.stream().map(GreetingProvider::greeting).collect(Collectors.toSet());
  }
}