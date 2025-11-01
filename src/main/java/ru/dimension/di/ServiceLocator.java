package ru.dimension.di;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Dimension-DI: tiny runtime locator for constructor-injected objects.
 *
 * - Keys are (type, optional name) to support jakarta.inject.Named
 * - Providers are Suppliers invoked lazily
 * - Singletons are cached via a wrapper
 * - Cycle detection guards against circular constructor graphs
 *
 * This class is intentionally simple and serves as the runtime "container".
 * Discovery and auto-registration are done by DependencyScanner + DimensionDI.Builder.
 */
public final class ServiceLocator {

  private ServiceLocator() {}

  // -----------------------------
  // Public key type
  // -----------------------------
  public static final class Key {
    public final Class<?> type;
    public final String name;

    Key(Class<?> type, String name) {
      this.type = Objects.requireNonNull(type, "type");
      this.name = (name == null || name.isBlank()) ? null : name;
    }

    public static Key of(Class<?> type) { return new Key(type, null); }
    public static Key of(Class<?> type, String name) { return new Key(type, name); }

    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Key k)) return false;
      return type.equals(k.type) && Objects.equals(name, k.name);
    }
    @Override public int hashCode() { return Objects.hash(type, name); }
    @Override public String toString() {
      return "Key{" + type.getName() + (name != null ? ", name=" + name : "") + "}";
    }
  }

  // -----------------------------
  // Provider registry and helpers
  // -----------------------------
  private static final Map<Key, Supplier<?>> providers = new ConcurrentHashMap<>();
  private static final ThreadLocal<Deque<Key>> creationStack = ThreadLocal.withInitial(ArrayDeque::new);

  public static void clear() { providers.clear(); }

  public static void init(Map<Key, Supplier<?>> map) {
    clear();
    providers.putAll(map);
  }

  // Basic registration API (use DimensionDI.Builder in normal code)
  public static <T> void registerProvider(Class<T> type, Supplier<? extends T> provider) {
    providers.put(Key.of(type), provider);
  }
  public static <T> void registerProvider(Class<T> type, String name, Supplier<? extends T> provider) {
    providers.put(Key.of(type, name), provider);
  }
  public static <T> void registerInstance(Class<T> type, T instance) {
    providers.put(Key.of(type), () -> instance);
  }
  public static <T> void registerInstance(Class<T> type, String name, T instance) {
    providers.put(Key.of(type, name), () -> instance);
  }
  public static void alias(Key alias, Key target) {
    Supplier<?> s = providers.get(target);
    if (s == null) throw new IllegalStateException("No provider for target: " + target);
    providers.put(alias, s);
  }
  public static <T> void override(Key key, Supplier<? extends T> provider) {
    providers.put(key, provider);
  }

  // Retrieval
  public static <T> T get(Class<T> type) { return type.cast(getInternal(Key.of(type))); }
  public static <T> T get(Class<T> type, String name) { return type.cast(getInternal(Key.of(type, name))); }

  private static Object getInternal(Key key) {
    Supplier<?> supplier = providers.get(key);
    if (supplier == null) {
      throw new IllegalStateException("No provider registered for " + key);
    }
    Deque<Key> stack = creationStack.get();
    if (stack.contains(key)) {
      throw new IllegalStateException("Circular dependency detected: " + stack + " -> " + key);
    }
    stack.push(key);
    try {
      return supplier.get();
    } finally {
      stack.pop();
    }
  }

  // -----------------------------
  // Utilities for Providers
  // -----------------------------

  // Wrap Supplier to become a singleton
  public static <T> Supplier<T> singleton(Supplier<T> delegate) {
    return new SingletonSupplier<>(delegate);
  }
  private static final class SingletonSupplier<T> implements Supplier<T> {
    private final Supplier<T> delegate;
    private volatile T instance;
    private SingletonSupplier(Supplier<T> delegate) { this.delegate = delegate; }
    @Override public T get() {
      T r = instance;
      if (r == null) {
        synchronized (this) {
          r = instance;
          if (r == null) instance = r = delegate.get();
        }
      }
      return r;
    }
  }

  /**
   * Create a provider from an @Inject (or default) constructor.
   * - Resolves parameter @Named values once and then uses ServiceLocator.get(...) at creation time.
   * - If singleton==true, result is wrapped in a singleton cache.
   */
  public static <T> Supplier<T> createConstructorProvider(Class<T> clazz, boolean singleton) {
    Constructor<T> ctor = findInjectConstructor(clazz);
    MethodHandle mh = unreflectConstructor(clazz, ctor);

    Parameter[] params = ctor.getParameters();
    String[] names = new String[params.length];
    Class<?>[] types = new Class<?>[params.length];
    for (int i = 0; i < params.length; i++) {
      types[i] = params[i].getType();
      jakarta.inject.Named named = params[i].getAnnotation(jakarta.inject.Named.class);
      names[i] = (named != null && !named.value().isBlank()) ? named.value() : null;
    }

    Supplier<T> s = () -> {
      Object[] args = new Object[types.length];
      for (int i = 0; i < types.length; i++) {
        args[i] = (names[i] != null) ? ServiceLocator.get(types[i], names[i]) : ServiceLocator.get(types[i]);
      }
      try {
        return (T) mh.invokeWithArguments(args);
      } catch (Throwable t) {
        throw new RuntimeException("Failed to instantiate " + clazz.getName(), t);
      }
    };
    return singleton ? singleton(s) : s;
  }

  @SuppressWarnings("unchecked")
  private static <T> Constructor<T> findInjectConstructor(Class<T> clazz) {
    Constructor<?>[] ctors = clazz.getDeclaredConstructors();
    Constructor<?> inject = null;
    for (Constructor<?> c : ctors) {
      if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
        if (inject != null) {
          throw new IllegalStateException("Multiple @Inject constructors in " + clazz.getName());
        }
        inject = c;
      }
    }
    if (inject == null) {
      try {
        inject = clazz.getDeclaredConstructor();
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException("No @Inject or default constructor in " + clazz.getName(), e);
      }
    }
    inject.setAccessible(true);
    return (Constructor<T>) inject;
  }

  private static <T> MethodHandle unreflectConstructor(Class<T> clazz, Constructor<T> ctor) {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(clazz, lookup);
      return privateLookup.unreflectConstructor(ctor);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Cannot access constructor of " + clazz.getName(), e);
    }
  }
}