package ru.dimension.di.multibind;

import jakarta.inject.Inject;

public class HelloGreetingProvider implements GreetingProvider {
  @Inject
  public HelloGreetingProvider() {}

  @Override
  public String greeting() {
    return "hello";
  }
}