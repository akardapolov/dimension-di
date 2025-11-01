package ru.dimension.di;

import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.dimension.di.beans.ConsumerBean;
import ru.dimension.di.beans.PrototypeBean;
import ru.dimension.di.beans.SingletonBean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the DimensionDI container, focusing on the end-to-end
 * flow from scanning to bean retrieval and scope verification.
 */
class DICommonTest {

  // The package where our test beans reside.
  private static final String TEST_BEANS_PACKAGE = "ru.dimension.di.beans";

  @BeforeEach
  void setUp() {
    // Clear the service locator state before each test to ensure isolation.
    ServiceLocator.clear();
  }

  @Nested
  @DisplayName("Bean Scope Tests")
  class ScopeTests {

    @Test
    @DisplayName("Prototype scope should return a new instance for each request")
    void prototypeScope_returnsNewInstance_forEachRequest() {
      // Arrange: Scan the package containing our test beans.
      DimensionDI.builder()
          .scanPackages(TEST_BEANS_PACKAGE)
          .buildAndInit();

      // Act: Request the PrototypeBean twice.
      PrototypeBean bean1 = ServiceLocator.get(PrototypeBean.class);
      PrototypeBean bean2 = ServiceLocator.get(PrototypeBean.class);

      // Assert: The two instances must not be the same.
      assertNotNull(bean1);
      assertNotNull(bean2);
      assertNotSame(bean1, bean2, "Prototype beans should be different instances.");
      assertNotEquals(bean1.id, bean2.id, "Prototype bean IDs should be different.");
    }

    @Test
    @DisplayName("Singleton scope should return the same instance for each request")
    void singletonScope_returnsSameInstance_forEachRequest() {
      // Arrange: Scan the package.
      DimensionDI.builder()
          .scanPackages(TEST_BEANS_PACKAGE)
          .buildAndInit();

      // Act: Request the SingletonBean twice.
      SingletonBean bean1 = ServiceLocator.get(SingletonBean.class);
      SingletonBean bean2 = ServiceLocator.get(SingletonBean.class);

      // Assert: The two instances must be the same.
      assertNotNull(bean1);
      assertNotNull(bean2);
      assertSame(bean1, bean2, "Singleton beans should be the same instance.");
      assertEquals(bean1.id, bean2.id, "Singleton bean IDs should be the same.");
    }
  }

  @Nested
  @DisplayName("Dependency Injection Tests")
  class InjectionTests {

    @Test
    @DisplayName("A consumer should receive correct instances based on scope")
    void consumerReceivesCorrectlyScopedInstances() {
      // Arrange: Scan the package.
      DimensionDI.builder()
          .scanPackages(TEST_BEANS_PACKAGE)
          .buildAndInit();

      // Act: Request a bean that consumes other beans.
      ConsumerBean consumer = ServiceLocator.get(ConsumerBean.class);

      // Assert
      assertNotNull(consumer, "Consumer bean should not be null.");
      assertNotNull(consumer.singletonBean, "Injected singleton should not be null.");
      assertNotNull(consumer.firstBean, "First injected prototype should not be null.");
      assertNotNull(consumer.secondBean, "Second injected prototype should not be null.");

      // Assert: The two injected prototype beans within the same consumer should be different instances.
      assertNotSame(consumer.firstBean, consumer.secondBean,
                    "Two injections of a prototype bean should result in two different instances.");

      // Assert: The injected singleton bean should be the same as one retrieved directly.
      SingletonBean directSingleton = ServiceLocator.get(SingletonBean.class);
      assertSame(consumer.singletonBean, directSingleton,
                 "Injected singleton must be the same instance as a directly retrieved one.");
    }

    @Test
    @DisplayName("Two consumers should share singletons but have unique prototypes")
    void twoConsumersShareSingletons() {
      // Arrange: Scan the package.
      DimensionDI.builder()
          .scanPackages(TEST_BEANS_PACKAGE)
          .buildAndInit();

      // Act
      ConsumerBean consumer1 = ServiceLocator.get(ConsumerBean.class);
      ConsumerBean consumer2 = ServiceLocator.get(ConsumerBean.class);

      // Assert
      assertNotSame(consumer1, consumer2, "The consumers themselves should be different (prototype scope).");

      // Assert: Both consumers should share the exact same singleton instance.
      assertSame(consumer1.singletonBean, consumer2.singletonBean,
                 "Both consumers must share the same SingletonBean instance.");

      // Assert: The prototype instances in each consumer should be unique.
      assertNotSame(consumer1.firstBean, consumer2.firstBean,
                    "Each consumer should get its own unique PrototypeBean instance.");
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Requesting an unregistered bean should throw IllegalStateException")
    void getUnregisteredBean_throwsException() {
      // Arrange: Initialize an empty container.
      DimensionDI.builder().buildAndInit();

      // Act & Assert
      IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
        ServiceLocator.get(String.class); // String is not a registered bean
      });

      assertTrue(exception.getMessage().contains("No provider registered for"),
                 "Exception message should indicate that no provider was found.");
    }

    @Test
    @DisplayName("Circular dependency should throw IllegalStateException")
    void circularDependency_throwsException() {
      // Arrange: Manually register two providers that depend on each other.
      ServiceLocator.registerProvider(ClassA.class, () -> new ClassA(ServiceLocator.get(ClassB.class)));
      ServiceLocator.registerProvider(ClassB.class, () -> new ClassB(ServiceLocator.get(ClassA.class)));

      // Act & Assert
      IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
        ServiceLocator.get(ClassA.class);
      });

      assertTrue(exception.getMessage().contains("Circular dependency detected"),
                 "Exception message should indicate a circular dependency.");
    }

    // Helper classes for the circular dependency test
    static class ClassA { @Inject public ClassA(ClassB b) {} }
    static class ClassB { @Inject public ClassB(ClassA a) {} }
  }
}
