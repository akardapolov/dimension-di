package ru.dimension.di;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.dimension.di.named.DefaultEventListenerImpl;
import ru.dimension.di.named.EventListener;
import ru.dimension.di.named.NamedRouter;
import ru.dimension.di.named.NamedRouterImpl;
import ru.dimension.di.named.SpecialEventListenerImpl;

class DINamedTest {

  @BeforeEach
  void setUp() {
    ServiceLocator.clear();
  }

  @AfterEach
  void tearDown() {
    // In case the executor is created, shut it down to avoid thread leaks
    try {
      NamedRouter r = ServiceLocator.get(NamedRouter.class, "router");
      if (r != null && r.getExecutor() != null) {
        r.getExecutor().shutdownNow();
      }
    } catch (Exception ignored) {}
  }

  @Test
  @DisplayName("Named bindings with jakarta annotations should resolve correctly")
  void namedBindingsAndJakartaAnnotationsWork() {
    DimensionDI.builder()
        // Scan our test-only package using jakarta.inject
        .scanPackages("ru.dimension.di.named")
        // Default binding for EventListener (non-named)
        .bind(EventListener.class, DefaultEventListenerImpl.class)
        // Named binding for EventListener
        .bindNamed(EventListener.class, "eventListener", SpecialEventListenerImpl.class)
        // Named binding for Router interface to implementation
        .bindNamed(NamedRouter.class, "router", NamedRouterImpl.class)
        // Provide @Named("executorService") for Router
        .provideNamed(
            ScheduledExecutorService.class,
            "executorService",
            ServiceLocator.singleton(Executors::newSingleThreadScheduledExecutor)
        )
        .buildAndInit();

    // Get router via named binding (like @Named("router"))
    NamedRouter r1 = ServiceLocator.get(NamedRouter.class, "router");
    NamedRouter r2 = ServiceLocator.get(NamedRouter.class, "router");

    assertNotNull(r1);
    assertSame(r1, r2, "Router should be a singleton (jakarta.inject.Singleton recognized).");

    // Ensure @Named("eventListener") picked the SpecialEventListenerImpl (not the default one)
    assertNotNull(r1.getEventListener());
    assertEquals(SpecialEventListenerImpl.class, r1.getEventListener().getClass(),
                 "@Named('eventListener') must resolve to SpecialEventListenerImpl");

    // Direct lookups also reflect bindings
    EventListener named = ServiceLocator.get(EventListener.class, "eventListener");
    EventListener defaultListener = ServiceLocator.get(EventListener.class);

    assertEquals(SpecialEventListenerImpl.class, named.getClass());
    assertEquals(DefaultEventListenerImpl.class, defaultListener.getClass());
  }
}