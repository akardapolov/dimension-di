package ru.dimension.di.named;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.ScheduledExecutorService;

@Singleton
public class NamedRouterImpl implements NamedRouter {

  private final ScheduledExecutorService executor;
  private final EventListener eventListener;

  @Inject
  public NamedRouterImpl(@Named("executorService") ScheduledExecutorService executor,
                         @Named("eventListener") EventListener eventListener) {
    this.executor = executor;
    this.eventListener = eventListener;
  }

  @Override
  public EventListener getEventListener() {
    return eventListener;
  }

  @Override
  public ScheduledExecutorService getExecutor() {
    return executor;
  }
}