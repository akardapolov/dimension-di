package ru.dimension.di;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import ru.dimension.di.ServiceLocator.Key;

/**
 * Main entry point for Dimension-DI configuration.
 * Use the fluent builder to set up dependency providers and then initialize the ServiceLocator.
 */
public final class DimensionDI {

  private DimensionDI() {}

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Set<String> packagesToScan = new HashSet<>();
    private final Map<Key, Supplier<?>> manualProviders = new HashMap<>();

    /**
     * Specifies the base packages to scan for classes with @Inject constructors.
     * You can call this multiple times to add more packages.
     *
     * @param packages The base package names (e.g., "ru.dimension.ui.services").
     * @return This builder instance for chaining.
     */
    public Builder scanPackages(String... packages) {
      packagesToScan.addAll(List.of(packages));
      return this;
    }

    /**
     * Binds an interface to a specific implementation class.
     * This is the equivalent of a Dagger @Binds method.
     *
     * @param interfaceType The interface class.
     * @param implementationType The implementation class.
     */
    public <T> Builder bind(Class<T> interfaceType, Class<? extends T> implementationType) {
      // The provider for the implementation will be created by the scanner.
      // We create a supplier that simply delegates to the ServiceLocator to get the implementation.
      Supplier<T> provider = () -> ServiceLocator.get(implementationType);
      manualProviders.put(Key.of(interfaceType), provider);
      return this;
    }

    /**
     * Binds a named interface to a specific implementation class.
     * This is the equivalent of a Dagger @Binds @Named method.
     */
    public <T> Builder bindNamed(Class<T> interfaceType, String name, Class<? extends T> implementationType) {
      Supplier<T> provider = () -> ServiceLocator.get(implementationType);
      manualProviders.put(new Key(interfaceType, name), provider);
      return this;
    }

    /**
     * Registers a custom provider for a type.
     * This is the equivalent of a Dagger @Provides method.
     * The provided supplier will be responsible for singleton logic if needed.
     * Use ServiceLocator.singleton() to wrap your supplier for @Singleton behavior.
     *
     * @param type The class of the object being provided.
     * @param provider A supplier that creates the object.
     */
    public <T> Builder provide(Class<T> type, Supplier<? extends T> provider) {
      manualProviders.put(Key.of(type), provider);
      return this;
    }

    /**
     * Registers a custom provider for a named type.
     * This is the equivalent of a Dagger @Provides @Named method.
     */
    public <T> Builder provideNamed(Class<T> type, String name, Supplier<? extends T> provider) {
      manualProviders.put(new Key(type, name), provider);
      return this;
    }

    /**
     * Scans the configured packages, combines with manual bindings,
     * and initializes the ServiceLocator. This should be called once at application startup.
     */
    public void buildAndInit() {
      Map<Key, Supplier<?>> allProviders = new HashMap<>();

      // 1. Run the scanner to discover all @Injectable classes
      if (!packagesToScan.isEmpty()) {
        List<DependencyScanner.ScanResult> scanResults = DependencyScanner.scan(packagesToScan.toArray(new String[0]));
        try {
          // Create providers for scanned classes
          for (var result : scanResults) {
            Class<?> clazz = Class.forName(result.className());
            Supplier<?> provider = ServiceLocator.createConstructorProvider(clazz, result.isSingleton());
            allProviders.put(Key.of(clazz), provider);
          }
        } catch (ClassNotFoundException e) {
          throw new RuntimeException("Dimension-DI: A class found during scan could not be loaded", e);
        }
      }

      // 2. Add manual providers, which will override any scanned providers for the same key.
      allProviders.putAll(manualProviders);

      // 3. Initialize the service locator with the final map.
      ServiceLocator.init(allProviders);
    }
  }
}