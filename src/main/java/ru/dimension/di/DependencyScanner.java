package ru.dimension.di;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans classpath for classes matching annotation-based rules using the Class-File API.
 *
 * Limitations (by design for "tiny DI"):
 * - Only "file:" and "jar:" classpath entries are supported.
 * - Module runtime image ("jrt:") and exotic classloaders are not handled.
 */
final class DependencyScanner {

  /**
   * Jar URL -> list of all .class entry names in that jar (like "a/b/C.class").
   * This prevents iterating the same jar over and over for multiple base packages.
   */
  private static final Map<String, List<String>> JAR_CLASS_ENTRY_CACHE = new ConcurrentHashMap<>();

  private DependencyScanner() {}

  /**
   * Config:
   * - injectConstructorAnnotations: any of these makes a constructor injectable
   * - singletonClassAnnotations: any of these marks a class as singleton
   * - allowPublicNoArgConstructor: treat public no-arg ctor as injectable (even without annotations)
   *
   * Annotation names can be given as FQCN ("jakarta.inject.Inject") or descriptors ("Ljakarta/inject/Inject;").
   */
  record Config(
      Set<String> injectConstructorAnnotations,
      Set<String> singletonClassAnnotations,
      boolean allowPublicNoArgConstructor
  ) {
    static Config defaultsJakartaInject() {
      return new Config(
          // you can pass FQCN or descriptors; normalization happens in canonical constructor
          Set.of("jakarta.inject.Inject"),
          Set.of("jakarta.inject.Singleton"),
          true
      );
    }

    Config {
      injectConstructorAnnotations = ClassFileAnnotations.normalizeAllToDescriptors(injectConstructorAnnotations);
      singletonClassAnnotations = ClassFileAnnotations.normalizeAllToDescriptors(singletonClassAnnotations);
    }
  }

  /**
   * Scan result.
   *
   * matchedInjectCtorAnnotations: union of matched inject-annotations found on at least one ctor
   * matchedSingletonAnnotations: subset of configured singleton annotations found on the class
   */
  record ScanResult(
      String className,
      boolean isSingleton,
      Set<String> interfaces,
      Set<String> matchedInjectCtorAnnotations,
      Set<String> matchedSingletonAnnotations
  ) {}

  public static List<ScanResult> scan(String... basePackages) {
    return scan(Config.defaultsJakartaInject(), basePackages);
  }

  public static List<ScanResult> scan(Config config, String... basePackages) {
    Objects.requireNonNull(config, "config");
    try {
      Set<String> classNames = discoverClassNames(basePackages);
      return analyzeClasses(config, classNames);
    } catch (Exception e) {
      throw new RuntimeException("Dimension-DI: Failed to scan packages: " + Arrays.toString(basePackages), e);
    }
  }

  private static List<ScanResult> analyzeClasses(Config config, Set<String> classNames) throws IOException {
    ClassLoader cl = effectiveClassLoader();

    List<ScanResult> results = new ArrayList<>();
    for (String className : classNames) {
      byte[] classBytes = readClassBytes(cl, className);
      ClassModel classModel = ClassFile.of().parse(classBytes);

      // skip things that can never be constructed
      if (classModel.flags().has(AccessFlag.ABSTRACT)) continue;
      if (classModel.flags().has(AccessFlag.INTERFACE)) continue;
      if (classModel.flags().has(AccessFlag.ANNOTATION)) continue;

      // Determine "injectable"
      boolean injectable = false;
      LinkedHashSet<String> matchedInjectCtorAnns = new LinkedHashSet<>();

      for (MethodModel m : classModel.methods()) {
        if (!m.methodName().stringValue().equals("<init>")) continue;

        // any configured @Inject-like annotation
        Set<String> matched = ClassFileAnnotations.findAnyAnnotations(m, config.injectConstructorAnnotations());
        if (!matched.isEmpty()) {
          injectable = true;
          matchedInjectCtorAnns.addAll(matched);
        }

        // optional implicit public no-arg ctor
        if (!injectable
            && config.allowPublicNoArgConstructor()
            && m.methodTypeSymbol().parameterCount() == 0
            && m.flags().has(AccessFlag.PUBLIC)) {
          injectable = true;
        }
      }

      if (!injectable) continue;

      Set<String> matchedSingleton = ClassFileAnnotations.findAnyAnnotations(
          classModel, config.singletonClassAnnotations());

      boolean isSingleton = !matchedSingleton.isEmpty();

      Set<String> interfaces = new LinkedHashSet<>();
      classModel.interfaces().forEach(iface -> interfaces.add(iface.name().stringValue().replace('/', '.')));

      results.add(new ScanResult(
          className,
          isSingleton,
          Collections.unmodifiableSet(interfaces),
          Collections.unmodifiableSet(matchedInjectCtorAnns),
          matchedSingleton
      ));
    }

    return results;
  }

  private static ClassLoader effectiveClassLoader() {
    ClassLoader tccl = Thread.currentThread().getContextClassLoader();
    return (tccl != null) ? tccl : DependencyScanner.class.getClassLoader();
  }

  private static byte[] readClassBytes(ClassLoader cl, String className) throws IOException {
    String resourceName = className.replace('.', '/') + ".class";
    try (InputStream is = cl.getResourceAsStream(resourceName)) {
      if (is == null) throw new IOException("Resource not found: " + resourceName);
      return is.readAllBytes();
    }
  }

  private static Set<String> discoverClassNames(String... basePackages) throws IOException {
    ClassLoader classLoader = effectiveClassLoader();
    Set<String> classNames = new LinkedHashSet<>();

    if (basePackages == null || basePackages.length == 0) return classNames;

    for (String basePackageRaw : basePackages) {
      if (basePackageRaw == null) continue;
      String basePackage = basePackageRaw.trim();
      if (basePackage.isEmpty()) continue;

      String path = basePackage.replace('.', '/');
      Enumeration<URL> resources = classLoader.getResources(path);

      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        String protocol = resource.getProtocol();

        if ("file".equals(protocol)) {
          try {
            classNames.addAll(findClassesInDirectory(basePackage, Paths.get(resource.toURI())));
          } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
          }
          continue;
        }

        if ("jar".equals(protocol)) {
          classNames.addAll(findClassesInJar(basePackage, resource));
          continue;
        }

        // ignore other protocols (jrt:, vfs:, etc.) for this tiny scanner
      }
    }

    return classNames;
  }

  private static Set<String> findClassesInDirectory(String basePackage, Path packageDir) throws IOException {
    if (!Files.isDirectory(packageDir)) return Set.of();

    LinkedHashSet<String> classes = new LinkedHashSet<>();

    // packageDir points to ".../ru/dimension/di" for basePackage "ru.dimension.di"
    try (var stream = Files.walk(packageDir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".class"))
          .forEach(p -> {
            String fileName = p.getFileName().toString();
            if (fileName.equals("package-info.class") || fileName.equals("module-info.class")) return;

            Path rel = packageDir.relativize(p);
            String relStr = rel.toString();

            // Normalize to '/' then to '.'
            relStr = relStr.replace(File.separatorChar, '/');

            if (!relStr.endsWith(".class")) return;
            String withoutExt = relStr.substring(0, relStr.length() - ".class".length());

            String className = basePackage + "." + withoutExt.replace('/', '.');
            classes.add(className);
          });
    }

    return Collections.unmodifiableSet(classes);
  }

  private static Set<String> findClassesInJar(String basePackage, URL jarPackageUrl) throws IOException {
    // jarPackageUrl looks like: jar:file:/.../x.jar!/ru/dimension/di
    JarURLConnection conn = (JarURLConnection) jarPackageUrl.openConnection();
    conn.setUseCaches(false);

    String jarId = conn.getJarFileURL().toExternalForm();
    String pathPrefix = basePackage.replace('.', '/') + "/";

    List<String> allClassEntries;
    try {
      allClassEntries = JAR_CLASS_ENTRY_CACHE.computeIfAbsent(jarId, _id -> {
        try {
          return listAllClassEntries(conn);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    } catch (UncheckedIOException uioe) {
      throw uioe.getCause();
    }

    LinkedHashSet<String> classes = new LinkedHashSet<>();
    for (String entryName : allClassEntries) {
      if (!entryName.startsWith(pathPrefix)) continue;
      if (!entryName.endsWith(".class")) continue;

      String simple = entryName.substring(entryName.lastIndexOf('/') + 1);
      if (simple.equals("package-info.class") || simple.equals("module-info.class")) continue;

      classes.add(entryName.replace('/', '.').replace(".class", ""));
    }

    return Collections.unmodifiableSet(classes);
  }

  private static List<String> listAllClassEntries(JarURLConnection conn) throws IOException {
    try (JarFile jar = conn.getJarFile()) {
      ArrayList<String> out = new ArrayList<>();
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry e = entries.nextElement();
        if (e.isDirectory()) continue;
        String name = e.getName();
        if (name.endsWith(".class")) {
          out.add(name);
        }
      }
      return List.copyOf(out);
    }
  }
}