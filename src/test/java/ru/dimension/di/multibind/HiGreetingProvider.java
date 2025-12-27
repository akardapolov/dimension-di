package ru.dimension.di.multibind;

import jakarta.inject.Inject;

public class HiGreetingProvider implements GreetingProvider {
  @Inject
  public HiGreetingProvider() {}

  @Override
  public String greeting() {
    return "hi";
  }
}