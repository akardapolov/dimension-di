package ru.dimension.di;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.dimension.di.multibind.GreetingConsumer;
import ru.dimension.di.multibind.GreetingProvider;
import ru.dimension.di.multibind.HelloGreetingProvider;
import ru.dimension.di.multibind.HiGreetingProvider;
import ru.dimension.di.multibind.QualifiedGreetingConsumer;

class DIMultiBindTest {

  @BeforeEach
  void setUp() {
    ServiceLocator.clear();
  }

  @Test
  @DisplayName("Scanning multi-binds interface implementations and injects List/Set/Map")
  void scanningMultibindCollectionsInjectionWorks() {
    DimensionDI.builder()
        .scanPackages("ru.dimension.di.multibind")
        .buildAndInit();

    GreetingConsumer consumer = ServiceLocator.get(GreetingConsumer.class);
    assertNotNull(consumer);

    // List/Set contain all implementations
    assertEquals(2, consumer.list().size());
    assertEquals(2, consumer.set().size());
    assertEquals(Set.of("hello", "hi"), consumer.greetings());

    // Map<String, T> includes named bindings (scanner names = impl simple names)
    Map<String, GreetingProvider> map = consumer.map();
    assertEquals(2, map.size());
    assertTrue(map.containsKey("HelloGreetingProvider"));
    assertTrue(map.containsKey("HiGreetingProvider"));
    assertEquals(Set.of("hello", "hi"),
                 Set.of(map.get("HelloGreetingProvider").greeting(), map.get("HiGreetingProvider").greeting()));

    assertEquals(2, ServiceLocator.getAll(GreetingProvider.class).size());
    assertEquals(2, ServiceLocator.getNamedMap(GreetingProvider.class).size());
  }

  @Test
  @DisplayName("Manual multi-bind via provideNamed injects collections and ServiceLocator.getAll/getNamedMap work")
  void manualProvideNamedMultibindWorks() {
    DimensionDI.builder()
        .provideNamed(GreetingProvider.class, "hello", HelloGreetingProvider::new)
        .provideNamed(GreetingProvider.class, "hi", HiGreetingProvider::new)
        .provide(GreetingConsumer.class, ServiceLocator.createConstructorProvider(GreetingConsumer.class, false))
        .buildAndInit();

    GreetingConsumer consumer = ServiceLocator.get(GreetingConsumer.class);
    assertNotNull(consumer);

    assertEquals(2, consumer.list().size());
    assertEquals(Set.of("hello", "hi"), consumer.greetings());

    // Map keys come from @Named
    assertEquals(2, consumer.map().size());
    assertTrue(consumer.map().containsKey("hello"));
    assertTrue(consumer.map().containsKey("hi"));

    assertEquals(2, ServiceLocator.getAll(GreetingProvider.class).size());
    assertEquals(2, ServiceLocator.getNamedMap(GreetingProvider.class).size());
  }

  @Test
  @DisplayName("@Named on collection injection point selects a single binding")
  void namedQualifierOnCollectionsSelectsSingleBinding() {
    DimensionDI.builder()
        .scanPackages("ru.dimension.di.multibind")
        .buildAndInit();

    QualifiedGreetingConsumer c = ServiceLocator.get(QualifiedGreetingConsumer.class);

    assertEquals(1, c.onlyHelloList().size());
    assertEquals("hello", c.onlyHelloList().getFirst().greeting());

    assertEquals(1, c.onlyHelloSet().size());
    assertEquals("hello", c.onlyHelloSet().iterator().next().greeting());

    assertEquals(1, c.onlyHelloMap().size());
    assertTrue(c.onlyHelloMap().containsKey("HelloGreetingProvider"));
    assertEquals("hello", c.onlyHelloMap().get("HelloGreetingProvider").greeting());
  }
}