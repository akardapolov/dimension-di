package ru.dimension.di;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Dimension-DI: tiny runtime locator for constructor-injected objects.
 *
 * Features include:
 * - Named bindings (jakarta.inject.Named)
 * - Scopes via singleton wrapper
 * - Cycle detection
 * - Assisted injection + factory generation
 * - Collections injection: {@code List<T>}, {@code Set<T>}, {@code Map<String,T>}
 * - Dagger-style explicit multibinding: intoSet / intoMap
 */
public final class ServiceLocator {

  private ServiceLocator() {}

  // =========================================================================
  // Public key type
  // =========================================================================

  public static final class Key {
    public final Class<?> type;
    public final String name;

    Key(Class<?> type, String name) {
      this.type = Objects.requireNonNull(type, "type");
      this.name = normalizeName(name);
    }

    public static Key of(Class<?> type) {
      return new Key(type, null);
    }

    public static Key of(Class<?> type, String name) {
      return new Key(type, name);
    }

    public Key unnamed() {
      return name == null ? this : new Key(type, null);
    }

    public boolean isNamed() {
      return name != null;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Key k)) return false;
      return type.equals(k.type) && Objects.equals(name, k.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, name);
    }

    @Override
    public String toString() {
      return "Key{" + type.getName() + (name != null ? ", name=" + name : "") + "}";
    }
  }

  private static String normalizeName(String name) {
    if (name == null) return null;
    String n = name.trim();
    return n.isEmpty() ? null : n;
  }

  // =========================================================================
  // Assisted argument wrapper
  // =========================================================================

  /**
   * Wrapper to pass named assisted arguments into {@link #create(Class, Object...)}.
   *
   * Example:
   * <pre>
   *   Foo foo = ServiceLocator.create(Foo.class,
   *       ServiceLocator.assisted("url", "jdbc:..."),
   *       ServiceLocator.assisted("timeoutMs", 1000));
   * </pre>
   */
  public record AssistedValue(String name, Object value) {
    public AssistedValue {
      name = normalizeName(name);
    }
  }

  public static AssistedValue assisted(String name, Object value) {
    return new AssistedValue(name, value);
  }

  // =========================================================================
  // Provider registry + cycle detection
  // =========================================================================

  private static final Map<Key, Supplier<?>> providers = new ConcurrentHashMap<>();
  private static final ThreadLocal<Deque<Key>> creationStack = ThreadLocal.withInitial(ArrayDeque::new);

  // =========================================================================
  // Dagger-style multibind registries
  // =========================================================================

  private static final Map<Class<?>, List<Supplier<?>>> intoSetContributions = new ConcurrentHashMap<>();
  private static final Map<Class<?>, Map<String, Supplier<?>>> intoMapContributions = new ConcurrentHashMap<>();

  public static void initMultibindings(
      Map<Class<?>, ? extends List<Supplier<?>>> sets,
      Map<Class<?>, ? extends Map<String, Supplier<?>>> maps
  ) {
    intoSetContributions.clear();
    intoMapContributions.clear();

    if (sets != null) {
      for (var e : sets.entrySet()) {
        intoSetContributions.put(e.getKey(), List.copyOf(e.getValue()));
      }
    }

    if (maps != null) {
      for (var e : maps.entrySet()) {
        LinkedHashMap<String, Supplier<?>> copy = new LinkedHashMap<>(e.getValue());
        intoMapContributions.put(e.getKey(), Collections.unmodifiableMap(copy));
      }
    }
  }

  // =========================================================================
  // Public config flags
  // =========================================================================

  private static volatile boolean namedFallbackEnabled = false;   // keep behavior required by tests
  private static volatile boolean unnamedFallbackEnabled = true;  // unnamed -> unique named allowed

  public static void setNamedFallbackEnabled(boolean enabled) {
    namedFallbackEnabled = enabled;
  }

  public static void setUnnamedFallbackEnabled(boolean enabled) {
    unnamedFallbackEnabled = enabled;
  }

  public static void clear() {
    providers.clear();
    intoSetContributions.clear();
    intoMapContributions.clear();
  }

  public static void init(Map<Key, Supplier<?>> map) {
    clear();
    providers.putAll(map);
  }

  // =========================================================================
  // Basic registration API
  // =========================================================================

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

  // =========================================================================
  // Retrieval API
  // =========================================================================

  public static <T> T get(Class<T> type) {
    return type.cast(getInternal(Key.of(type)));
  }

  public static <T> T get(Class<T> type, String name) {
    return type.cast(getInternal(Key.of(type, name)));
  }

  public static boolean has(Class<?> type) {
    if (providers.containsKey(Key.of(type))) return true;
    return unnamedFallbackEnabled && findUniqueNamedBinding(type) != null;
  }

  public static boolean has(Class<?> type, String name) {
    if (providers.containsKey(Key.of(type, name))) return true;
    return namedFallbackEnabled && providers.containsKey(Key.of(type));
  }

  // =========================================================================
  // Multi-bind helper accessors
  // =========================================================================

  /**
   * Returns all bindings for this type as a deterministic list:
   * unnamed first (if exists), then named sorted by name.
   *
   * Deduplicates by supplier identity to avoid alias duplicates.
   */
  public static <T> List<T> getAll(Class<T> type) {
    List<Key> keys = keysForType(type);

    IdentityHashMap<Supplier<?>, Boolean> seen = new IdentityHashMap<>();
    List<KeySupplier> unique = new ArrayList<>();

    for (Key k : keys) {
      Supplier<?> s = providers.get(k);
      if (s == null) continue;
      if (seen.putIfAbsent(s, Boolean.TRUE) == null) {
        unique.add(new KeySupplier(k, s));
      }
    }

    List<T> out = new ArrayList<>(unique.size());
    for (KeySupplier ks : unique) {
      Object o = callWithCycleDetection(ks.key, ks.supplier);
      out.add(type.cast(o));
    }
    return List.copyOf(out);
  }

  public static <T> Set<T> getAllSet(Class<T> type) {
    return Set.copyOf(new LinkedHashSet<>(getAll(type)));
  }

  /**
   * Returns all named bindings for this type as a sorted map.
   * (Unnamed binding is NOT included.)
   */
  public static <T> Map<String, T> getNamedMap(Class<T> type) {
    TreeMap<String, T> out = new TreeMap<>();
    for (Key k : providers.keySet()) {
      if (k.type.equals(type) && k.isNamed()) {
        out.put(k.name, type.cast(getInternal(k)));
      }
    }
    return Map.copyOf(out);
  }

  // =========================================================================
  // Core resolution method
  // =========================================================================

  private static Object getInternal(Key key) {
    Supplier<?> supplier = providers.get(key);

    // Fallback: unnamed -> single named
    if (supplier == null && key.name == null && unnamedFallbackEnabled) {
      Supplier<?> onlyNamed = findSingleNamedProviderForType(key.type);
      if (onlyNamed != null) {
        supplier = onlyNamed;
      }
    }

    // Optional fallback: named -> unnamed
    if (supplier == null && key.isNamed() && namedFallbackEnabled) {
      supplier = providers.get(key.unnamed());
    }

    if (supplier == null) {
      throw new IllegalStateException("No provider registered for " + key);
    }

    return callWithCycleDetection(key, supplier);
  }

  private static Object callWithCycleDetection(Key key, Supplier<?> supplier) {
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

  private static Supplier<?> findSingleNamedProviderForType(Class<?> type) {
    Supplier<?> found = null;
    for (Map.Entry<Key, Supplier<?>> e : providers.entrySet()) {
      Key k = e.getKey();
      if (k.type.equals(type) && k.name != null) {
        if (found != null) return null; // ambiguous
        found = e.getValue();
      }
    }
    return found;
  }

  private static Key findUniqueNamedBinding(Class<?> type) {
    Key found = null;
    for (Key k : providers.keySet()) {
      if (k.type.equals(type) && k.isNamed()) {
        if (found != null) return null;
        found = k;
      }
    }
    return found;
  }

  private static List<Key> keysForType(Class<?> type) {
    List<Key> named = new ArrayList<>();
    Key unnamed = Key.of(type);
    boolean hasUnnamed = providers.containsKey(unnamed);

    for (Key k : providers.keySet()) {
      if (k.type.equals(type) && k.isNamed()) {
        named.add(k);
      }
    }
    named.sort(Comparator.comparing(a -> a.name));

    List<Key> out = new ArrayList<>(named.size() + (hasUnnamed ? 1 : 0));
    if (hasUnnamed) out.add(unnamed);
    out.addAll(named);
    return out;
  }

  private record KeySupplier(Key key, Supplier<?> supplier) {}

  // =========================================================================
  // Singleton wrapper
  // =========================================================================

  public static <T> Supplier<T> singleton(Supplier<T> delegate) {
    return new SingletonSupplier<>(delegate);
  }

  private static final class SingletonSupplier<T> implements Supplier<T> {
    private final Supplier<T> delegate;
    private volatile T instance;

    private SingletonSupplier(Supplier<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public T get() {
      T r = instance;
      if (r == null) {
        synchronized (this) {
          r = instance;
          if (r == null) {
            instance = r = delegate.get();
          }
        }
      }
      return r;
    }
  }

  // =========================================================================
  // Dependency resolution (supports collections + explicit multibind)
  // =========================================================================

  private record Dependency(Class<?> rawType, Type genericType, String named) {}

  private static String readNamed(jakarta.inject.Named named) {
    if (named == null) return null;
    String v = named.value();
    return (v == null || v.isBlank()) ? null : v;
  }

  private static boolean hasIntoSet(Class<?> elementType) {
    List<Supplier<?>> l = intoSetContributions.get(elementType);
    return l != null && !l.isEmpty();
  }

  private static boolean hasIntoMap(Class<?> valueType) {
    Map<String, Supplier<?>> m = intoMapContributions.get(valueType);
    return m != null && !m.isEmpty();
  }

  private static List<?> resolveIntoSetAsList(Class<?> elementType) {
    List<Supplier<?>> contrib = intoSetContributions.get(elementType);
    if (contrib == null) return List.of();

    List<Object> out = new ArrayList<>(contrib.size());
    for (Supplier<?> s : contrib) out.add(s.get());
    return List.copyOf(out);
  }

  private static Set<?> resolveIntoSetAsSet(Class<?> elementType) {
    List<Supplier<?>> contrib = intoSetContributions.get(elementType);
    if (contrib == null) return Set.of();

    LinkedHashSet<Object> out = new LinkedHashSet<>();
    for (Supplier<?> s : contrib) out.add(s.get());
    return Set.copyOf(out);
  }

  private static Map<String, ?> resolveIntoMap(Class<?> valueType) {
    Map<String, Supplier<?>> contrib = intoMapContributions.get(valueType);
    if (contrib == null) return Map.of();

    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    for (var e : contrib.entrySet()) {
      out.put(e.getKey(), e.getValue().get());
    }
    return Collections.unmodifiableMap(out);
  }

  private static Object resolveDependency(Dependency dep) {
    Class<?> raw = dep.rawType;
    String named = dep.named;

    // List<T> / Collection<T>
    if (raw == List.class || raw == Collection.class) {
      Class<?> elem = extractSingleGeneric(dep.genericType, 0, "List/Collection");

      if (named != null) {
        Object one = get(elem, named);
        return List.of(one);
      }

      // Explicit multibind takes precedence
      if (hasIntoSet(elem)) {
        return resolveIntoSetAsList(elem);
      }

      return getAll(elem);
    }

    // Set<T>
    if (raw == Set.class) {
      Class<?> elem = extractSingleGeneric(dep.genericType, 0, "Set");

      if (named != null) {
        Object one = get(elem, named);
        return Set.of(one);
      }

      if (hasIntoSet(elem)) {
        return resolveIntoSetAsSet(elem);
      }

      return getAllSet(elem);
    }

    // Map<String, T>
    if (raw == Map.class) {
      if (!(dep.genericType instanceof ParameterizedType pt) || pt.getActualTypeArguments().length != 2) {
        throw new IllegalStateException("Map injection requires Map<String, T> with generics");
      }
      Class<?> keyType = typeToClass(pt.getActualTypeArguments()[0]);
      if (keyType != String.class) {
        throw new IllegalStateException("Map injection only supports Map<String, T>, got Map<"
                                            + keyType.getName() + ", ...>");
      }
      Class<?> valueType = typeToClass(pt.getActualTypeArguments()[1]);

      if (named != null) {
        Object one = get(valueType, named);
        return Map.of(named, one);
      }

      if (hasIntoMap(valueType)) {
        return resolveIntoMap(valueType);
      }

      return getNamedMap(valueType);
    }

    // Normal single binding
    Key key = named != null ? Key.of(raw, named) : Key.of(raw);
    return getInternal(key);
  }

  private static Class<?> extractSingleGeneric(Type genericType, int idx, String context) {
    if (!(genericType instanceof ParameterizedType pt)) {
      throw new IllegalStateException(context + " injection requires a parameterized type like " + context + "<T>");
    }
    Type[] args = pt.getActualTypeArguments();
    if (args.length <= idx) {
      throw new IllegalStateException(context + " injection missing generic parameter");
    }
    return typeToClass(args[idx]);
  }

  private static Class<?> typeToClass(Type t) {
    if (t instanceof Class<?> c) return c;

    if (t instanceof ParameterizedType pt) {
      Type raw = pt.getRawType();
      if (raw instanceof Class<?> c) return c;
    }

    if (t instanceof WildcardType wt) {
      Type[] upper = wt.getUpperBounds();
      if (upper.length == 1) return typeToClass(upper[0]);
    }

    if (t instanceof TypeVariable<?>) {
      throw new IllegalStateException("Type variables are not supported for DI generics: " + t);
    }

    throw new IllegalStateException("Unsupported generic type for DI: " + t);
  }

  // =========================================================================
  // Constructor-based provider creation
  // =========================================================================

  public static <T> Supplier<T> createConstructorProvider(Class<T> clazz, boolean singleton) {
    Constructor<?> ctor = findInjectConstructor(clazz);

    boolean hasAssistedParams = Arrays.stream(ctor.getParameters())
        .anyMatch(p -> p.isAnnotationPresent(Assisted.class));

    if (hasAssistedParams) {
      return () -> {
        throw new IllegalStateException(
            "Class " + clazz.getName() + " has @Assisted parameters and must be created via a factory. " +
                "Use ServiceLocator.create() or register a factory with DimensionDI.Builder.bindFactory()");
      };
    }

    MethodHandle mh = unreflectConstructor(clazz, ctor);

    Parameter[] params = ctor.getParameters();
    Dependency[] deps = new Dependency[params.length];
    for (int i = 0; i < params.length; i++) {
      Parameter p = params[i];
      String name = readNamed(p.getAnnotation(jakarta.inject.Named.class));
      deps[i] = new Dependency(p.getType(), p.getParameterizedType(), name);
    }

    Supplier<T> s = () -> {
      Object[] args = new Object[deps.length];
      for (int i = 0; i < deps.length; i++) {
        args[i] = resolveDependency(deps[i]);
      }

      final T instance;
      try {
        @SuppressWarnings("unchecked")
        T tmp = (T) mh.invokeWithArguments(args);
        instance = tmp;
      } catch (Throwable t) {
        if (t instanceof IllegalStateException ise) throw ise;
        if (t instanceof RuntimeException re) throw re;
        throw new RuntimeException("Failed to instantiate " + clazz.getName(), t);
      }

      injectMembers(instance);
      return instance;
    };

    return singleton ? singleton(s) : s;
  }

  // =========================================================================
  // Member injection (field and method)
  // =========================================================================

  public static void injectMembers(Object instance) {
    if (instance == null) return;

    Deque<Class<?>> hierarchy = new ArrayDeque<>();
    for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      hierarchy.push(c);
    }

    while (!hierarchy.isEmpty()) {
      Class<?> c = hierarchy.pop();

      // Field Injection
      for (Field f : c.getDeclaredFields()) {
        if (f.isSynthetic()) continue;
        if (!f.isAnnotationPresent(jakarta.inject.Inject.class)) continue;

        int mod = f.getModifiers();
        if (Modifier.isStatic(mod)) continue;
        if (Modifier.isFinal(mod)) {
          throw new IllegalStateException("Cannot inject into final field: " + c.getName() + "#" + f.getName());
        }

        String name = readNamed(f.getAnnotation(jakarta.inject.Named.class));
        Dependency dep = new Dependency(f.getType(), f.getGenericType(), name);
        Object value = resolveDependency(dep);

        try {
          if (!f.canAccess(instance) && !f.trySetAccessible()) {
            throw new IllegalStateException("Cannot access field for injection: " + c.getName() + "#" + f.getName());
          }
          f.set(instance, value);
        } catch (IllegalAccessException e) {
          throw new RuntimeException("Failed to inject field: " + c.getName() + "#" + f.getName(), e);
        }
      }

      // Method Injection
      for (Method m : c.getDeclaredMethods()) {
        if (m.isSynthetic() || m.isBridge()) continue;
        if (!m.isAnnotationPresent(jakarta.inject.Inject.class)) continue;

        int mod = m.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isAbstract(mod)) {
          throw new IllegalStateException(
              "Cannot inject into static or abstract method: " + c.getName() + "#" + m.getName());
        }

        Parameter[] params = m.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
          Parameter p = params[i];
          String name = readNamed(p.getAnnotation(jakarta.inject.Named.class));
          args[i] = resolveDependency(new Dependency(p.getType(), p.getParameterizedType(), name));
        }

        try {
          if (!m.canAccess(instance) && !m.trySetAccessible()) {
            throw new IllegalStateException("Cannot access method for injection: " + c.getName() + "#" + m.getName());
          }
          m.invoke(instance, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
          throw new RuntimeException("Failed to inject method: " + c.getName() + "#" + m.getName(), e);
        }
      }
    }
  }

  // =========================================================================
  // Assisted injection - Direct creation
  // =========================================================================

  @SuppressWarnings("unchecked")
  public static <T> T create(Class<T> clazz, Object... assistedArgs) {
    Constructor<?> ctor = findInjectConstructor(clazz);
    Parameter[] params = ctor.getParameters();
    Object[] args = resolveAssistedParameters(params, assistedArgs);

    try {
      MethodHandle mh = unreflectConstructor(clazz, ctor);
      T instance = (T) mh.invokeWithArguments(args);
      injectMembers(instance);
      return instance;
    } catch (Throwable t) {
      if (t instanceof RuntimeException re) throw re;
      throw new RuntimeException("Failed to create " + clazz.getName(), t);
    }
  }

  private static final class AssistedArg {
    final String name;   // normalized (null if absent)
    final Object value;

    AssistedArg(String name, Object value) {
      this.name = normalizeName(name);
      this.value = value;
    }
  }

  private static Object[] resolveAssistedParameters(Parameter[] params, Object[] assistedArgsRaw) {
    Object[] assistedArgsRawSafe = (assistedArgsRaw == null) ? new Object[0] : assistedArgsRaw;

    AssistedArg[] assistedArgs = new AssistedArg[assistedArgsRawSafe.length];
    for (int i = 0; i < assistedArgsRawSafe.length; i++) {
      Object a = assistedArgsRawSafe[i];
      if (a instanceof AssistedValue av) {
        assistedArgs[i] = new AssistedArg(av.name(), av.value());
      } else {
        assistedArgs[i] = new AssistedArg(null, a);
      }
    }

    Object[] args = new Object[params.length];
    boolean[] assistedUsed = new boolean[assistedArgs.length];

    for (int i = 0; i < params.length; i++) {
      Parameter p = params[i];
      Assisted assisted = p.getAnnotation(Assisted.class);

      if (assisted != null) {
        String assistedName = normalizeName(assisted.value());
        int matchIdx = findMatchingAssistedArg(p.getType(), assistedName, assistedArgs, assistedUsed);
        if (matchIdx == -1) {
          throw new IllegalArgumentException(
              "No matching assisted argument for parameter " + p.getType().getName() +
                  (assistedName == null ? "" : " named '" + assistedName + "'") +
                  " at index " + i);
        }
        assistedUsed[matchIdx] = true;
        args[i] = assistedArgs[matchIdx].value;
      } else {
        String name = readNamed(p.getAnnotation(jakarta.inject.Named.class));
        args[i] = resolveDependency(new Dependency(p.getType(), p.getParameterizedType(), name));
      }
    }

    // Stronger validation: do not silently ignore extra assisted args
    for (int i = 0; i < assistedUsed.length; i++) {
      if (!assistedUsed[i]) {
        AssistedArg a = assistedArgs[i];
        throw new IllegalArgumentException(
            "Unused assisted argument at index " + i +
                (a.name != null ? " (name='" + a.name + "')" : "") +
                (a.value != null ? " of type " + a.value.getClass().getName() : " (null)")
        );
      }
    }

    return args;
  }

  /**
   * Matching rules:
   * - If assistedName != null: match only args with the same name AND type-compatible.
   * - If assistedName == null: match unnamed args by type (first match wins).
   *
   * Null assisted values are ignored (cannot infer type).
   */
  private static int findMatchingAssistedArg(
      Class<?> paramType,
      String assistedName,
      AssistedArg[] args,
      boolean[] used
  ) {
    // Pass 1: strict named matching (if name is required)
    if (assistedName != null) {
      for (int i = 0; i < args.length; i++) {
        if (used[i]) continue;
        AssistedArg a = args[i];
        if (!assistedName.equals(a.name)) continue;
        if (a.value == null) continue;
        if (isTypeCompatible(paramType, a.value.getClass())) return i;
      }
      return -1;
    }

    // Pass 2: unnamed by direct instance check
    for (int i = 0; i < args.length; i++) {
      if (used[i]) continue;
      AssistedArg a = args[i];
      if (a.name != null) continue; // do not use named arg for unnamed param
      if (a.value == null) continue;
      if (paramType.isInstance(a.value)) return i;
    }

    // Pass 3: unnamed with primitive boxing compatibility
    for (int i = 0; i < args.length; i++) {
      if (used[i]) continue;
      AssistedArg a = args[i];
      if (a.name != null) continue;
      if (a.value == null) continue;
      if (isTypeCompatible(paramType, a.value.getClass())) return i;
    }

    return -1;
  }

  // =========================================================================
  // Assisted injection - Factory creation
  // =========================================================================

  @SuppressWarnings("unchecked")
  public static <F> F createFactory(Class<F> factoryInterface, Class<?> targetClass) {
    validateFactoryInterface(factoryInterface);

    Method factoryMethod = findFactoryMethod(factoryInterface);

    // sanity: factory return type should match targetClass
    if (!factoryMethod.getReturnType().isAssignableFrom(targetClass)
        && !targetClass.isAssignableFrom(factoryMethod.getReturnType())) {
      // Keep it permissive but fail if it is clearly incompatible
      throw new IllegalArgumentException(
          "Factory method return type " + factoryMethod.getReturnType().getName() +
              " is incompatible with targetClass " + targetClass.getName());
    }

    Constructor<?> ctor = findInjectConstructor(targetClass);
    MethodHandle mh = unreflectConstructor(targetClass, ctor);

    Parameter[] ctorParams = ctor.getParameters();
    ParameterMapping[] mappings = buildParameterMappings(ctorParams, factoryMethod.getParameters());

    return (F) Proxy.newProxyInstance(
        factoryInterface.getClassLoader(),
        new Class<?>[] { factoryInterface },
        (proxy, method, args) -> {
          if (method.equals(factoryMethod)) {
            return invokeFactory(mh, ctorParams, mappings, args);
          }
          return handleObjectMethod(proxy, method, args);
        }
    );
  }

  public static <F> Supplier<F> createFactorySupplier(Class<F> factoryInterface, Class<?> targetClass) {
    return singleton(() -> createFactory(factoryInterface, targetClass));
  }

  public static <F> F createFactory(Class<F> factoryInterface) {
    Method factoryMethod = findFactoryMethod(factoryInterface);
    Class<?> targetClass = factoryMethod.getReturnType();
    return createFactory(factoryInterface, targetClass);
  }

  private static void validateFactoryInterface(Class<?> factoryInterface) {
    if (!factoryInterface.isInterface()) {
      throw new IllegalArgumentException(factoryInterface.getName() + " is not an interface");
    }
  }

  private static Method findFactoryMethod(Class<?> factoryInterface) {
    Method factoryMethod = null;
    for (Method m : factoryInterface.getMethods()) {
      if (!m.isDefault() && !Modifier.isStatic(m.getModifiers())
          && m.getDeclaringClass() != Object.class) {
        if (factoryMethod != null) {
          throw new IllegalArgumentException(
              "Factory interface must have exactly one abstract method: " + factoryInterface.getName());
        }
        factoryMethod = m;
      }
    }
    if (factoryMethod == null) {
      throw new IllegalArgumentException(
          "Factory interface has no abstract method: " + factoryInterface.getName());
    }
    return factoryMethod;
  }

  private sealed interface ParameterMapping {
    record FromDI(Dependency dep) implements ParameterMapping {}
    record FromFactory(int factoryParamIndex) implements ParameterMapping {}
  }

  private static ParameterMapping[] buildParameterMappings(Parameter[] ctorParams, Parameter[] factoryParams) {
    ParameterMapping[] mappings = new ParameterMapping[ctorParams.length];
    boolean[] factoryParamUsed = new boolean[factoryParams.length];

    for (int i = 0; i < ctorParams.length; i++) {
      Parameter p = ctorParams[i];
      Assisted assisted = p.getAnnotation(Assisted.class);

      if (assisted != null) {
        int matchIndex = findMatchingFactoryParam(p, normalizeName(assisted.value()), factoryParams, factoryParamUsed);
        if (matchIndex == -1) {
          throw new IllegalArgumentException(
              "No matching factory parameter for @Assisted " + p.getType().getName() +
                  (normalizeName(assisted.value()) == null ? "" : " named '" + normalizeName(assisted.value()) + "'"));
        }
        factoryParamUsed[matchIndex] = true;
        mappings[i] = new ParameterMapping.FromFactory(matchIndex);
      } else {
        String name = readNamed(p.getAnnotation(jakarta.inject.Named.class));
        mappings[i] = new ParameterMapping.FromDI(new Dependency(p.getType(), p.getParameterizedType(), name));
      }
    }

    for (int i = 0; i < factoryParamUsed.length; i++) {
      if (!factoryParamUsed[i]) {
        throw new IllegalArgumentException(
            "Factory parameter at index " + i + " (" + factoryParams[i].getType().getName() +
                ") does not match any @Assisted constructor parameter");
      }
    }

    return mappings;
  }

  private static int findMatchingFactoryParam(
      Parameter ctorParam,
      String assistedName,
      Parameter[] factoryParams,
      boolean[] used
  ) {
    Class<?> ctorParamType = ctorParam.getType();

    // Prefer exact name match if name is specified
    if (assistedName != null) {
      for (int j = 0; j < factoryParams.length; j++) {
        if (used[j]) continue;
        Parameter fp = factoryParams[j];
        if (!isTypeCompatible(ctorParamType, fp.getType())) continue;

        Assisted fAssisted = fp.getAnnotation(Assisted.class);
        String fName = (fAssisted != null) ? normalizeName(fAssisted.value()) : null;

        if (assistedName.equals(fName)) return j;
      }
      return -1;
    }

    // Unnamed assisted param: match any compatible factory param that is also unnamed
    for (int j = 0; j < factoryParams.length; j++) {
      if (used[j]) continue;
      Parameter fp = factoryParams[j];
      if (!isTypeCompatible(ctorParamType, fp.getType())) continue;

      Assisted fAssisted = fp.getAnnotation(Assisted.class);
      String fName = (fAssisted != null) ? normalizeName(fAssisted.value()) : null;

      if (fName == null) return j;
    }

    return -1;
  }

  // =========================================================================
  // Type compatibility helpers (improved: no per-call Map allocation)
  // =========================================================================

  private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = Map.of(
      boolean.class, Boolean.class,
      byte.class, Byte.class,
      char.class, Character.class,
      short.class, Short.class,
      int.class, Integer.class,
      long.class, Long.class,
      float.class, Float.class,
      double.class, Double.class
  );

  private static Class<?> wrapPrimitive(Class<?> c) {
    Class<?> w = PRIMITIVE_TO_WRAPPER.get(c);
    return (w != null) ? w : c;
  }

  private static boolean isTypeCompatible(Class<?> targetParamType, Class<?> providedType) {
    if (targetParamType.isAssignableFrom(providedType)) return true;

    Class<?> t = targetParamType.isPrimitive() ? wrapPrimitive(targetParamType) : targetParamType;
    Class<?> p = providedType.isPrimitive() ? wrapPrimitive(providedType) : providedType;

    return t.isAssignableFrom(p);
  }

  // =========================================================================
  // Factory invocation helpers
  // =========================================================================

  private static Object invokeFactory(
      MethodHandle mh,
      Parameter[] ctorParams,
      ParameterMapping[] mappings,
      Object[] factoryArgs
  ) throws Throwable {
    Object[] args = new Object[ctorParams.length];
    for (int i = 0; i < mappings.length; i++) {
      args[i] = switch (mappings[i]) {
        case ParameterMapping.FromDI(Dependency dep) -> resolveDependency(dep);
        case ParameterMapping.FromFactory(int idx) -> factoryArgs != null ? factoryArgs[idx] : null;
      };
    }

    Object instance = mh.invokeWithArguments(args);
    injectMembers(instance);
    return instance;
  }

  private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "toString" -> proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> {
        Object other = (args == null || args.length == 0) ? null : args[0];
        yield proxy == other;
      }
      default -> throw new UnsupportedOperationException(method.toString());
    };
  }

  // =========================================================================
  // Constructor utilities
  // =========================================================================

  private static Constructor<?> findInjectConstructor(Class<?> clazz) {
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

    // Prefer trySetAccessible over setAccessible for clearer failure on strong encapsulation
    if (!inject.canAccess(null) && !inject.trySetAccessible()) {
      throw new IllegalStateException("Cannot access constructor of " + clazz.getName() + ": " + inject);
    }

    return inject;
  }

  private static MethodHandle unreflectConstructor(Class<?> clazz, Constructor<?> ctor) {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(clazz, lookup);
      return privateLookup.unreflectConstructor(ctor);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Cannot access constructor of " + clazz.getName(), e);
    }
  }
}