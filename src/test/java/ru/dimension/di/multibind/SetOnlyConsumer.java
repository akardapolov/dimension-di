package ru.dimension.di.multibind;

import jakarta.inject.Inject;
import java.util.Set;

public class SetOnlyConsumer {
  public final Set<GreetingProvider> providers;

  @Inject
  public SetOnlyConsumer(Set<GreetingProvider> providers) {
    this.providers = providers;
  }
}