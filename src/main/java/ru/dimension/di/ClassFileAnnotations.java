package ru.dimension.di;

import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.util.*;

/**
 * Small helper around Class-File API runtime annotations.
 *
 * Notes:
 * - Class-File API represents annotation type as a descriptor like "Lpkg/Foo;".
 * - We accept either descriptor form or FQCN ("pkg.Foo") in config and normalize.
 */
final class ClassFileAnnotations {

  private ClassFileAnnotations() {}

  static String normalizeToDescriptor(String fqcnOrDescriptor) {
    if (fqcnOrDescriptor == null) return null;
    String s = fqcnOrDescriptor.trim();
    if (s.isEmpty()) return null;

    // Descriptor form: Ljava/lang/Deprecated;
    if (s.length() >= 3 && s.charAt(0) == 'L' && s.charAt(s.length() - 1) == ';') {
      return s;
    }

    // Treat as FQCN: java.lang.Deprecated
    return "L" + s.replace('.', '/') + ";";
  }

  static Set<String> normalizeAllToDescriptors(Collection<String> fqcnOrDescriptors) {
    if (fqcnOrDescriptors == null || fqcnOrDescriptors.isEmpty()) return Set.of();
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String s : fqcnOrDescriptors) {
      String d = normalizeToDescriptor(s);
      if (d != null) out.add(d);
    }
    return Collections.unmodifiableSet(out);
  }

  /**
   * Returns descriptors of all runtime-visible + runtime-invisible annotations
   * present on the given element.
   */
  static Set<String> runtimeAnnotationDescriptors(AttributedElement element) {
    if (element == null) return Set.of();

    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (var attr : element.attributes()) {
      List<Annotation> annotations = null;
      if (attr instanceof RuntimeVisibleAnnotationsAttribute rva) {
        annotations = rva.annotations();
      } else if (attr instanceof RuntimeInvisibleAnnotationsAttribute ria) {
        annotations = ria.annotations();
      }

      if (annotations == null || annotations.isEmpty()) continue;

      for (Annotation a : annotations) {
        out.add(a.className().stringValue());
      }
    }
    return Collections.unmodifiableSet(out);
  }

  static boolean hasAnyAnnotation(AttributedElement element, Set<String> wantedDescriptors) {
    if (wantedDescriptors == null || wantedDescriptors.isEmpty()) return false;
    for (String present : runtimeAnnotationDescriptors(element)) {
      if (wantedDescriptors.contains(present)) return true;
    }
    return false;
  }

  /**
   * Returns the subset of wanted descriptors present on element.
   */
  static Set<String> findAnyAnnotations(AttributedElement element, Set<String> wantedDescriptors) {
    if (wantedDescriptors == null || wantedDescriptors.isEmpty()) return Set.of();

    LinkedHashSet<String> matched = new LinkedHashSet<>();
    for (String present : runtimeAnnotationDescriptors(element)) {
      if (wantedDescriptors.contains(present)) {
        matched.add(present);
      }
    }
    return Collections.unmodifiableSet(matched);
  }
}