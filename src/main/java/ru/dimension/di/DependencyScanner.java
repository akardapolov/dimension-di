package ru.dimension.di;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.*;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.reflect.AccessFlag;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans the classpath for classes with JSR-330 annotations using the Class-File API.
 * This version supports jakarta.inject.* only.
 */
final class DependencyScanner {

  private static final String INJECT_DESC = "Ljakarta/inject/Inject;";
  private static final String SINGLETON_DESC = "Ljakarta/inject/Singleton;";

  private DependencyScanner() {}

  record ScanResult(String className, boolean isSingleton, Set<String> interfaces) {}

  public static List<ScanResult> scan(String... basePackages) {
    try {
      Set<String> classNames = discoverClassNames(basePackages);
      return analyzeClasses(classNames);
    } catch (Exception e) {
      throw new RuntimeException("Dimension-DI: Failed to scan packages: " + Arrays.toString(basePackages), e);
    }
  }

  private static List<ScanResult> analyzeClasses(Set<String> classNames) throws IOException {
    List<ScanResult> results = new ArrayList<>();
    for (String className : classNames) {
      byte[] classBytes = readClassBytes(className);
      ClassModel classModel = ClassFile.of().parse(classBytes);

      // We only care about non-abstract classes with an injectable constructor
      if (classModel.flags().has(AccessFlag.ABSTRACT)) continue;

      boolean hasInjectConstructor = classModel.methods().stream()
          .anyMatch(m -> m.methodName().stringValue().equals("<init>") && hasAnnotation(m, INJECT_DESC));

      // Also consider classes with a public no-arg constructor as implicitly injectable
      boolean hasDefaultConstructor = classModel.methods().stream()
          .anyMatch(m -> m.methodName().stringValue().equals("<init>")
              && m.methodTypeSymbol().parameterCount() == 0
              && m.flags().has(AccessFlag.PUBLIC));

      if (hasInjectConstructor || hasDefaultConstructor) {
        boolean isSingleton = hasAnnotation(classModel, SINGLETON_DESC);
        Set<String> interfaces = new HashSet<>();
        classModel.interfaces().forEach(iface -> interfaces.add(iface.name().stringValue().replace('/', '.')));
        results.add(new ScanResult(className, isSingleton, interfaces));
      }
    }
    return results;
  }

  private static boolean hasAnnotation(AttributedElement element, String descriptor) {
    for (var attr : element.attributes()) {
      List<Annotation> annotations = null;
      if (attr instanceof RuntimeVisibleAnnotationsAttribute rva) {
        annotations = rva.annotations();
      } else if (attr instanceof RuntimeInvisibleAnnotationsAttribute ria) {
        annotations = ria.annotations();
      }
      if (annotations != null) {
        for (Annotation a : annotations) {
          if (a.className().stringValue().equals(descriptor)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static byte[] readClassBytes(String className) throws IOException {
    String resourceName = className.replace('.', '/') + ".class";
    try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
      if (is == null) throw new IOException("Resource not found: " + resourceName);
      return is.readAllBytes();
    }
  }

  private static Set<String> discoverClassNames(String... basePackages) throws IOException {
    Set<String> classNames = new HashSet<>();
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    for (String basePackage : basePackages) {
      String path = basePackage.replace('.', '/');
      Enumeration<URL> resources = classLoader.getResources(path);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        if ("file".equals(resource.getProtocol())) {
          try {
            classNames.addAll(findClassesInDirectory(basePackage, Paths.get(resource.toURI())));
          } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
          }
        } else if ("jar".equals(resource.getProtocol())) {
          classNames.addAll(findClassesInJar(basePackage, resource));
        }
      }
    }
    return classNames;
  }

  private static Set<String> findClassesInDirectory(String basePackage, Path directory) throws IOException {
    Set<String> classes = new HashSet<>();
    if (!Files.isDirectory(directory)) return classes;
    String packagePathPart = basePackage.replace('.', File.separatorChar);
    try (var stream = Files.walk(directory)) {
      stream.filter(p -> p.toString().endsWith(".class"))
          .forEach(path -> {
            String fullPath = path.toString();
            int packageStartIndex = fullPath.indexOf(packagePathPart);
            if (packageStartIndex != -1) {
              String className = fullPath
                  .substring(packageStartIndex)
                  .replace(File.separatorChar, '.')
                  .replace(".class", "");
              classes.add(className);
            }
          });
    }
    return classes;
  }

  private static Set<String> findClassesInJar(String basePackage, URL jarUrl) throws IOException {
    Set<String> classes = new HashSet<>();
    JarURLConnection conn = (JarURLConnection) jarUrl.openConnection();
    String pathPrefix = basePackage.replace('.', '/') + "/";
    try (JarFile jar = conn.getJarFile()) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (name.startsWith(pathPrefix) && name.endsWith(".class") && !entry.isDirectory()) {
          classes.add(name.replace('/', '.').replace(".class", ""));
        }
      }
    }
    return classes;
  }
}