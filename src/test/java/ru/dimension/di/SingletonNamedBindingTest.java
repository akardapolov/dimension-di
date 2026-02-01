package ru.dimension.di;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for singleton behavior with named bindings.
 * Demonstrates the bug where provideNamed with singleton() creates a separate instance
 * from the scanned singleton.
 */
class SingletonNamedBindingTest {

  @BeforeEach
  void setUp() {
    ServiceLocator.clear();
    TestSingletonService.resetCounter();
    TestNonSingletonService.resetCounter();
  }

  // =========================================================================
  // Test classes
  // =========================================================================

  @Singleton
  public static class TestSingletonService {
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);
    private final int instanceId;

    @Inject
    public TestSingletonService() {
      this.instanceId = INSTANCE_COUNTER.incrementAndGet();
    }

    public int getInstanceId() {
      return instanceId;
    }

    public static int getInstanceCount() {
      return INSTANCE_COUNTER.get();
    }

    public static void resetCounter() {
      INSTANCE_COUNTER.set(0);
    }
  }

  public static class TestNonSingletonService {
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);
    private final int instanceId;

    @Inject
    public TestNonSingletonService() {
      this.instanceId = INSTANCE_COUNTER.incrementAndGet();
    }

    public int getInstanceId() {
      return instanceId;
    }

    public static int getInstanceCount() {
      return INSTANCE_COUNTER.get();
    }

    public static void resetCounter() {
      INSTANCE_COUNTER.set(0);
    }
  }

  public static class ServiceConsumer {
    private final TestSingletonService service;

    @Inject
    public ServiceConsumer(@Named("myService") TestSingletonService service) {
      this.service = service;
    }

    public TestSingletonService getService() {
      return service;
    }
  }

  // =========================================================================
  // Bug Demonstration Tests
  // =========================================================================

  @Nested
  @DisplayName("Bug: provideNamed with singleton() creates duplicate instances")
  class ProvideNamedBugTests {

    @Test
    @DisplayName("BUG: provideNamed + singleton() creates TWO instances when class is also scanned")
    void provideNamedWithSingletonCreatesDuplicateInstance() {
      // This demonstrates the bug the user reported
      DimensionDI.builder()
          .scanPackages("ru.dimension.di")  // Scans TestSingletonService
          .provideNamed(TestSingletonService.class, "myService",
                        ServiceLocator.singleton(TestSingletonService::new))
          .buildAndInit();

      // Access via unnamed key (from scanner)
      TestSingletonService unnamed = ServiceLocator.get(TestSingletonService.class);

      // Access via named key (from provideNamed)
      TestSingletonService named = ServiceLocator.get(TestSingletonService.class, "myService");

      // BUG: Two instances were created!
      assertEquals(2, TestSingletonService.getInstanceCount(),
                   "BUG: Two singleton instances were created!");

      assertNotSame(unnamed, named,
                    "BUG: Named and unnamed bindings return different instances!");

      assertEquals(1, unnamed.getInstanceId());
      assertEquals(2, named.getInstanceId());
    }

    @Test
    @DisplayName("BUG: Each access to different keys triggers separate singleton initialization")
    void separateSingletonsForEachKey() {
      DimensionDI.builder()
          .provideNamed(TestSingletonService.class, "service1",
                        ServiceLocator.singleton(TestSingletonService::new))
          .provideNamed(TestSingletonService.class, "service2",
                        ServiceLocator.singleton(TestSingletonService::new))
          .buildAndInit();

      TestSingletonService s1 = ServiceLocator.get(TestSingletonService.class, "service1");
      TestSingletonService s2 = ServiceLocator.get(TestSingletonService.class, "service2");

      // Each named binding has its own singleton
      assertEquals(2, TestSingletonService.getInstanceCount());
      assertNotSame(s1, s2);
    }
  }

  // =========================================================================
  // Working Solutions Tests
  // =========================================================================

  @Nested
  @DisplayName("Solution: bindNamed delegates to scanner's singleton")
  class BindNamedSolutionTests {

    @Test
    @DisplayName("bindNamed correctly shares scanner's singleton instance")
    void bindNamedSharesScannerSingleton() {
      DimensionDI.builder()
          .scanPackages("ru.dimension.di")
          .bindNamed(TestSingletonService.class, "myService", TestSingletonService.class)
          .buildAndInit();

      TestSingletonService unnamed = ServiceLocator.get(TestSingletonService.class);
      TestSingletonService named = ServiceLocator.get(TestSingletonService.class, "myService");

      // Only ONE instance created
      assertEquals(1, TestSingletonService.getInstanceCount(),
                   "bindNamed should share the scanner's singleton");

      assertSame(unnamed, named,
                 "Named and unnamed should return the same instance");
    }

    @Test
    @DisplayName("Multiple bindNamed to same impl class share singleton")
    void multipleBindNamedShareSingleton() {
      DimensionDI.builder()
          .scanPackages("ru.dimension.di")
          .bindNamed(TestSingletonService.class, "alias1", TestSingletonService.class)
          .bindNamed(TestSingletonService.class, "alias2", TestSingletonService.class)
          .buildAndInit();

      TestSingletonService s1 = ServiceLocator.get(TestSingletonService.class, "alias1");
      TestSingletonService s2 = ServiceLocator.get(TestSingletonService.class, "alias2");
      TestSingletonService unnamed = ServiceLocator.get(TestSingletonService.class);

      assertEquals(1, TestSingletonService.getInstanceCount());
      assertSame(s1, s2);
      assertSame(s1, unnamed);
    }
  }

  @Nested
  @DisplayName("Solution: provideNamed with ServiceLocator.get() delegation")
  class ProvideNamedWithDelegationTests {

    @Test
    @DisplayName("provideNamed delegating to ServiceLocator.get() shares singleton")
    void provideNamedWithDelegationSharesSingleton() {
      DimensionDI.builder()
          .scanPackages("ru.dimension.di")
          // Correct way: delegate to container instead of creating new instance
          .provideNamed(TestSingletonService.class, "myService",
                        () -> ServiceLocator.get(TestSingletonService.class))
          .buildAndInit();

      TestSingletonService unnamed = ServiceLocator.get(TestSingletonService.class);
      TestSingletonService named = ServiceLocator.get(TestSingletonService.class, "myService");

      assertEquals(1, TestSingletonService.getInstanceCount());
      assertSame(unnamed, named);
    }
  }

  @Nested
  @DisplayName("Solution: Don't scan when using manual singleton provider")
  class NoScanSolutionTests {

    @Test
    @DisplayName("Without scanning, provideNamed singleton works correctly")
    void provideNamedWithoutScanningWorks() {
      // Don't scan the package - register manually only
      DimensionDI.builder()
          // No scanPackages for this class
          .provideNamed(TestSingletonService.class, "myService",
                        ServiceLocator.singleton(TestSingletonService::new))
          .buildAndInit();

      // Only the named binding exists
      TestSingletonService named1 = ServiceLocator.get(TestSingletonService.class, "myService");
      TestSingletonService named2 = ServiceLocator.get(TestSingletonService.class, "myService");

      assertEquals(1, TestSingletonService.getInstanceCount());
      assertSame(named1, named2);

      // Unnamed access works due to auto-aliasing (unique named binding)
      TestSingletonService unnamed = ServiceLocator.get(TestSingletonService.class);
      assertSame(named1, unnamed);
    }
  }

  // =========================================================================
  // Edge Cases
  // =========================================================================

  @Nested
  @DisplayName("Edge cases and expected behaviors")
  class EdgeCaseTests {

    @Test
    @DisplayName("provideSingleton helper method should work correctly")
    void provideSingletonHelper() {
      DimensionDI.builder()
          .provideSingleton(TestNonSingletonService.class,
                            TestNonSingletonService::new)
          .buildAndInit();

      TestNonSingletonService s1 = ServiceLocator.get(TestNonSingletonService.class);
      TestNonSingletonService s2 = ServiceLocator.get(TestNonSingletonService.class);

      assertEquals(1, TestNonSingletonService.getInstanceCount());
      assertSame(s1, s2);
    }

    @Test
    @DisplayName("Manual provider overrides scanned provider for same key")
    void manualProviderOverridesScanner() {
      TestSingletonService customInstance = new TestSingletonService(); // instance #1

      DimensionDI.builder()
          .scanPackages("ru.dimension.di")  // Would register TestSingletonService
          // Override with our instance
          .instance(TestSingletonService.class, customInstance)
          .buildAndInit();

      TestSingletonService retrieved = ServiceLocator.get(TestSingletonService.class);

      // Our instance was used, not scanner's
      assertSame(customInstance, retrieved);
      assertEquals(1, TestSingletonService.getInstanceCount());
    }

    @Test
    @DisplayName("Injection with @Named uses correct singleton")
    void namedInjectionUsesSingleton() {
      DimensionDI.builder()
          .scanPackages("ru.dimension.di")
          .bindNamed(TestSingletonService.class, "myService", TestSingletonService.class)
          .buildAndInit();

      ServiceConsumer consumer = ServiceLocator.get(ServiceConsumer.class);

      assertEquals(1, TestSingletonService.getInstanceCount());
      assertSame(
          ServiceLocator.get(TestSingletonService.class),
          consumer.getService()
      );
    }
  }
}