package ru.dimension.di;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.dimension.di.multibind.*;

class DIDaggerStyleMultibindTest {

  @BeforeEach
  void setUp() {
    ServiceLocator.clear();
  }

  @Test
  @DisplayName("intoSet overrides implicit 'collect all bindings' behavior for Set injection")
  void intoSetOverridesImplicitForSetInjection() {
    DimensionDI.builder()
        .scanPackages("ru.dimension.di.multibind")
        // Even though scan finds HelloGreetingProvider + HiGreetingProvider,
        // Set<GreetingProvider> should come ONLY from explicit contributions.
        .intoSet(GreetingProvider.class, () -> ServiceLocator.get(HelloGreetingProvider.class))
        .buildAndInit();

    SetOnlyConsumer c = ServiceLocator.get(SetOnlyConsumer.class);
    assertNotNull(c);

    assertEquals(1, c.providers.size());
    assertEquals("hello", c.providers.iterator().next().greeting());
  }

  @Test
  @DisplayName("intoMap provides Map<String, T> from explicit contributions (not from all named bindings)")
  void intoMapProvidesExplicitMap() {
    DimensionDI.builder()
        .scanPackages("ru.dimension.di.multibind")
        .intoMap(GreetingProvider.class, "h", () -> ServiceLocator.get(HelloGreetingProvider.class))
        .intoMap(GreetingProvider.class, "i", () -> ServiceLocator.get(HiGreetingProvider.class))
        .buildAndInit();

    MapOnlyConsumer c = ServiceLocator.get(MapOnlyConsumer.class);
    assertNotNull(c);

    assertEquals(2, c.map.size());
    assertTrue(c.map.containsKey("h"));
    assertTrue(c.map.containsKey("i"));
    assertEquals(Set.of("hello", "hi"),
                 Set.of(c.map.get("h").greeting(), c.map.get("i").greeting()));
  }

  @Test
  @DisplayName("Duplicate intoMap keys are rejected")
  void duplicateIntoMapKeyRejected() {
    DimensionDI.Builder b = DimensionDI.builder()
        .scanPackages("ru.dimension.di.multibind")
        .intoMap(GreetingProvider.class, "dup", () -> ServiceLocator.get(HelloGreetingProvider.class));

    assertThrows(IllegalArgumentException.class, () ->
        b.intoMap(GreetingProvider.class, "dup", () -> ServiceLocator.get(HiGreetingProvider.class)));
  }

  @Test
  @DisplayName("intoSetSingleton caches the contributed instance across injections")
  void intoSetSingletonCachesInstance() {
    DimensionDI.builder()
        .scanPackages("ru.dimension.di.multibind")
        .intoSetSingleton(GreetingProvider.class, () -> ServiceLocator.get(HelloGreetingProvider.class))
        .buildAndInit();

    SetOnlyConsumer c1 = ServiceLocator.get(SetOnlyConsumer.class);
    SetOnlyConsumer c2 = ServiceLocator.get(SetOnlyConsumer.class);

    GreetingProvider p1 = c1.providers.iterator().next();
    GreetingProvider p2 = c2.providers.iterator().next();

    assertSame(p1, p2, "Singleton contribution should return the same instance across injections");
  }
}