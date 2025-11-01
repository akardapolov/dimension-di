package ru.dimension.di.named;

import java.util.concurrent.ScheduledExecutorService;

public interface NamedRouter {
  EventListener getEventListener();
  ScheduledExecutorService getExecutor();
}
