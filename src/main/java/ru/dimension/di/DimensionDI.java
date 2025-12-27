package ru.dimension.di;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Supplier;
import ru.dimension.di.ServiceLocator.Key;

public final class DimensionDI {

  private DimensionDI() {}

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Set<String> packagesToScan = new HashSet<>();
    private final Map<Key, Supplier<?>> manualProviders = new HashMap<>();
    private final List<FactoryBinding<?>> factoryBindings = new ArrayList<>();
    private boolean autoAliasUniqueNamed = true;

    // Scanner config
    private DependencyScanner.Config scannerConfig = DependencyScanner.Config.defaultsJakartaInject();

    // Dagger-style multibind contributions
    private final Map<Class<?>, List<Supplier<?>>> intoSetContributions = new HashMap<>();
    private final Map<Class<?>, LinkedHashMap<String, Supplier<?>>> intoMapContributions = new HashMap<>();

    public Builder scanPackages(String... packages) {
      packagesToScan.addAll(List.of(packages));
      return this;
    }

    /**
     * Scanner configuration.
     * Annotation names may be FQCN ("jakarta.inject.Inject") or descriptors ("Ljakarta/inject/Inject;").
     */
    public Builder scannerConfig(DependencyScanner.Config config) {
      this.scannerConfig = Objects.requireNonNull(config, "config");
      return this;
    }

    /**
     * Convenience: override what is considered an "inject constructor annotation".
     */
    public Builder injectConstructorAnnotations(String... annotationsFqcnOrDesc) {
      Set<String> s = new LinkedHashSet<>(List.of(annotationsFqcnOrDesc));
      this.scannerConfig = new DependencyScanner.Config(
          s,
          this.scannerConfig.singletonClassAnnotations(),
          this.scannerConfig.allowPublicNoArgConstructor()
      );
      return this;
    }

    /**
     * Convenience: override what is considered a "singleton class annotation".
     */
    public Builder singletonClassAnnotations(String... annotationsFqcnOrDesc) {
      Set<String> s = new LinkedHashSet<>(List.of(annotationsFqcnOrDesc));
      this.scannerConfig = new DependencyScanner.Config(
          this.scannerConfig.injectConstructorAnnotations(),
          s,
          this.scannerConfig.allowPublicNoArgConstructor()
      );
      return this;
    }

    public Builder allowImplicitPublicNoArgConstructor(boolean enabled) {
      this.scannerConfig = new DependencyScanner.Config(
          this.scannerConfig.injectConstructorAnnotations(),
          this.scannerConfig.singletonClassAnnotations(),
          enabled
      );
      return this;
    }

    /**
     * Controls whether to automatically create unnamed aliases for types
     * that have exactly one named binding.
     * Default is true.
     */
    public Builder autoAliasUniqueNamed(boolean enabled) {
      this.autoAliasUniqueNamed = enabled;
      return this;
    }

    public <T> Builder bind(Class<T> interfaceType, Class<? extends T> implementationType) {
      Supplier<T> provider = () -> ServiceLocator.get(implementationType);
      manualProviders.put(Key.of(interfaceType), provider);
      return this;
    }

    public <T> Builder bindNamed(Class<T> interfaceType, String name, Class<? extends T> implementationType) {
      Supplier<T> provider = () -> ServiceLocator.get(implementationType);
      manualProviders.put(new Key(interfaceType, name), provider);
      return this;
    }

    public <T> Builder provide(Class<T> type, Supplier<? extends T> provider) {
      manualProviders.put(Key.of(type), provider);
      return this;
    }

    public <T> Builder provideSingleton(Class<T> type, Supplier<? extends T> provider) {
      manualProviders.put(Key.of(type), ServiceLocator.singleton(provider));
      return this;
    }

    public <T> Builder provideNamed(Class<T> type, String name, Supplier<? extends T> provider) {
      manualProviders.put(new Key(type, name), provider);
      return this;
    }

    public <T> Builder instance(Class<T> type, T instance) {
      manualProviders.put(Key.of(type), () -> instance);
      return this;
    }

    public <T> Builder instanceNamed(Class<T> type, String name, T instance) {
      manualProviders.put(new Key(type, name), () -> instance);
      return this;
    }

    // =========================================================================
    // Dagger-style multibind APIs
    // =========================================================================

    /**
     * Contribute one element into {@code Set<T>} (and also {@code List<T>}/{@code Collection<T>} if injected).
     */
    public <T> Builder intoSet(Class<T> elementType, Supplier<? extends T> contribution) {
      Objects.requireNonNull(elementType, "elementType");
      Objects.requireNonNull(contribution, "contribution");
      intoSetContributions
          .computeIfAbsent(elementType, k -> new ArrayList<>())
          .add((Supplier<?>) contribution);
      return this;
    }

    public <T> Builder intoSetSingleton(Class<T> elementType, Supplier<? extends T> contribution) {
      return intoSet(elementType, ServiceLocator.singleton(contribution));
    }

    /**
     * Contribute one entry into {@code Map<String, T>}.
     */
    public <T> Builder intoMap(Class<T> valueType, String key, Supplier<? extends T> contribution) {
      Objects.requireNonNull(valueType, "valueType");
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("intoMap key must be non-blank");
      }
      Objects.requireNonNull(contribution, "contribution");

      LinkedHashMap<String, Supplier<?>> m =
          intoMapContributions.computeIfAbsent(valueType, k -> new LinkedHashMap<>());

      if (m.containsKey(key)) {
        throw new IllegalArgumentException(
            "Duplicate intoMap key '" + key + "' for value type " + valueType.getName());
      }

      m.put(key, (Supplier<?>) contribution);
      return this;
    }

    public <T> Builder intoMapSingleton(Class<T> valueType, String key, Supplier<? extends T> contribution) {
      return intoMap(valueType, key, ServiceLocator.singleton(contribution));
    }

    // =========================================================================
    // Assisted factories
    // =========================================================================

    public <F> Builder bindFactory(Class<F> factoryInterface, Class<?> targetClass) {
      factoryBindings.add(new FactoryBinding<>(factoryInterface, targetClass));
      return this;
    }

    public <F> Builder bindFactory(Class<F> factoryInterface) {
      Class<?> targetClass = inferTargetClass(factoryInterface);
      factoryBindings.add(new FactoryBinding<>(factoryInterface, targetClass));
      return this;
    }

    private Class<?> inferTargetClass(Class<?> factoryInterface) {
      for (Method method : factoryInterface.getMethods()) {
        if (!method.isDefault() && !Modifier.isStatic(method.getModifiers())
            && method.getDeclaringClass() != Object.class) {
          return method.getReturnType();
        }
      }
      throw new IllegalArgumentException(
          "Cannot infer target class from factory interface: " + factoryInterface.getName());
    }

    public void buildAndInit() {
      Map<Key, Supplier<?>> allProviders = new HashMap<>();

      // 1. Run the scanner
      if (!packagesToScan.isEmpty()) {
        List<DependencyScanner.ScanResult> scanResults =
            DependencyScanner.scan(scannerConfig, packagesToScan.toArray(new String[0]));

        try {
          for (var result : scanResults) {
            Class<?> clazz = Class.forName(result.className());
            Supplier<?> provider = ServiceLocator.createConstructorProvider(clazz, result.isSingleton());

            // Register the class itself
            allProviders.put(Key.of(clazz), provider);

            // Interface bindings:
            // - ensure unnamed binding exists (first wins)
            // - also register named binding for each impl (impl simple name / fallback collision handling)
            for (String ifaceName : result.interfaces()) {
              try {
                Class<?> iface = Class.forName(ifaceName);
                Key unnamedIfaceKey = Key.of(iface);

                if (!allProviders.containsKey(unnamedIfaceKey) && !manualProviders.containsKey(unnamedIfaceKey)) {
                  allProviders.put(unnamedIfaceKey, provider);
                }

                String implName = clazz.getSimpleName();
                Key namedIfaceKey = Key.of(iface, implName);

                if (allProviders.containsKey(namedIfaceKey) || manualProviders.containsKey(namedIfaceKey)) {
                  implName = clazz.getName();
                  namedIfaceKey = Key.of(iface, implName);
                }

                if (allProviders.containsKey(namedIfaceKey) || manualProviders.containsKey(namedIfaceKey)) {
                  int suffix = 2;
                  Key candidate;
                  do {
                    candidate = Key.of(iface, implName + "#" + suffix);
                    suffix++;
                  } while (allProviders.containsKey(candidate) || manualProviders.containsKey(candidate));
                  namedIfaceKey = candidate;
                }

                if (!allProviders.containsKey(namedIfaceKey) && !manualProviders.containsKey(namedIfaceKey)) {
                  allProviders.put(namedIfaceKey, provider);
                }

              } catch (ClassNotFoundException ignored) {}
            }
          }
        } catch (ClassNotFoundException e) {
          throw new RuntimeException("Dimension-DI: A class found during scan could not be loaded", e);
        }
      }

      // 2. Add manual providers
      allProviders.putAll(manualProviders);

      // 3. Auto-create unnamed aliases for unique named bindings
      if (autoAliasUniqueNamed) {
        createUnnamedAliases(allProviders);
      }

      // 4. Initialize providers + multibind contributions
      ServiceLocator.init(allProviders);
      ServiceLocator.initMultibindings(intoSetContributions, intoMapContributions);

      // 5. Register factories
      for (var binding : factoryBindings) {
        registerFactory(binding);
      }
    }

    private void createUnnamedAliases(Map<Key, Supplier<?>> allProviders) {
      Map<Class<?>, List<Key>> namedKeysByType = new HashMap<>();
      Set<Class<?>> typesWithUnnamed = new HashSet<>();

      for (Key key : allProviders.keySet()) {
        if (key.isNamed()) {
          namedKeysByType.computeIfAbsent(key.type, k -> new ArrayList<>()).add(key);
        } else {
          typesWithUnnamed.add(key.type);
        }
      }

      for (var entry : namedKeysByType.entrySet()) {
        Class<?> type = entry.getKey();
        List<Key> namedKeys = entry.getValue();

        if (namedKeys.size() == 1 && !typesWithUnnamed.contains(type)) {
          Key namedKey = namedKeys.get(0);
          Key unnamedKey = Key.of(type);
          allProviders.put(unnamedKey, allProviders.get(namedKey));
        }
      }
    }

    private <F> void registerFactory(FactoryBinding<F> binding) {
      Supplier<F> factorySupplier = ServiceLocator.createFactorySupplier(
          binding.factoryInterface, binding.targetClass);
      ServiceLocator.registerProvider(binding.factoryInterface, factorySupplier);
    }

    private record FactoryBinding<F>(Class<F> factoryInterface, Class<?> targetClass) {}
  }
}