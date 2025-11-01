# Dimension-DI

Tiny, fast, zero-boilerplate runtime Dependency Injection (DI) framework for Java.

## Contents

- [Why Dimension-DI?](#why-dimension-di)
- [Core Philosophy](#core-philosophy)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
    - [1. Define Your Components](#1-define-your-components)
    - [2. Configure and Initialize](#2-configure-and-initialize)
    - [Binding Interfaces and Named Implementations](#binding-interfaces-and-named-implementations)
    - [Named implementations via @Named](#named-implementations-via-named)
    - [Custom Providers (like @Provides)](#custom-providers-like-provides)
    - [Skipping Scanning (Manual Wiring Only)](#skipping-scanning-manual-wiring-only)
    - [Testing and Overriding](#testing-and-overriding)
- [How It Works](#how-it-works)
    - [DependencyScanner](#dependencyscanner)
    - [DimensionDI.Builder](#dimensiondibuilder)
    - [ServiceLocator](#servicelocator)
- [Design Notes: DI vs Service Locator](#design-notes-di-vs-service-locator)
- [Limitations](#limitations)
- [API Cheatsheet](#api-cheatsheet)
    - [Bootstrap](#bootstrap)
    - [Runtime Fetch (Composition Root Only)](#runtime-fetch-composition-root-only)
    - [Manual Registration (on Builder)](#manual-registration-on-builder)
    - [Utilities](#utilities)
- [Comparison Tables](#comparison-tables)
- [Documentation](#documentation)
- [Contact](#contact)

This framework provides dependency injection (DI) based on JSR-330 (jakarta.inject.*) annotations. It automatically discovers and wires your application's components through constructor injection, leveraging classpath scanning near a zero-configuration setup. Designed for simplicity and fast startup, it's perfect for smaller applications, microservices, and tools that need the benefits of DI without the overhead associated with larger frameworks like Spring, Guice, and Dagger 2.

![Schema](media/schema.png)

<details>
  <summary>Mermaid schema</summary>

```mermaid
graph TB
    subgraph config["⚙️ Configuration JSR-330"]
        direction LR
        CA["@Inject<br/>Constructor"] --> CB["@Singleton<br/>Scope"] --> CC["@Named<br/>Qualifier"]
    end

    subgraph container["📦 DI Container"]
        direction LR
        ContainerDesc["<large>API Scans bytecode <br/>via JDK Class-File API</large>"] --> D1["DependencyScanner"] --> D2["Provider Registry"] --> D3["ServiceLocator"]
    end

    subgraph injection["💉 Dependency Resolution"]
        direction LR
        InjectionDesc["<large>Implements the Service<br/>Locator design pattern</large>"] --> I1["Request Type"] --> I2["Check Cache"] --> I3["Create Instance"] --> I4["Return Object"]
    end

    config -->|is analyzed by| container
    container -->|resolves dependencies via| injection
    injection -->|provides entrypoint to| APP["🎯 Your App<br/><small>Composition Root</small>"]

%% Styles
    classDef configClass fill:#e3f2fd,stroke:#90caf9,stroke-width:2px,color:#333
    classDef containerClass fill:#fff3e0,stroke:#ffcc80,stroke-width:2px,color:#333
    classDef injectionClass fill:#f3e5f5,stroke:#ce93d8,stroke-width:2px,color:#333
    classDef appClass fill:#e8f5e9,stroke:#a5d6a7,stroke-width:2px,color:#333
    classDef descClass fill:none,stroke:none,color:#555,font-size:12px,font-style:italic

    class CA,CB,CC configClass
    class D1,D2,D3 containerClass
    class I1,I2,I3,I4 injectionClass
    class APP appClass
    class CHeader descClass
```
</details>

## Why Dimension-DI?

Dimension-DI is a lightweight, runtime-oriented dependency injection container designed for simplicity and performance. It delivers the essential features you need without the complexity:

- **Standards-Based:** Uses JSR-330 (@Inject, @Named) for clean, constructor-injected code.
- **Powerful & Safe:** Features @Singleton scoping, circular dependency detection, and explicit binding for interfaces.
- **Fast & Efficient:** Employs classpath scanning via the JDK Class-File API (without loading classes) for rapid startup, working seamlessly from both directories and JARs.
- **Minimal Overhead**: No proxies, bytecode generation, or runtime magic—just a simple, thread-safe service locator under the hood that you'll never need to touch in your business logic.

## Core Philosophy

Dimension-DI follows a two-phase approach:

1.  **Build-Time Configuration:** A fluent `Builder` API is used to configure the DI container. This phase involves scanning the classpath for components marked with `@Inject`, analyzing their dependencies, and registering providers (recipes for creating objects). This is where the "DI" part shines.
2.  **Runtime Resolution:** At runtime, dependencies are resolved using an internal, globally accessible `ServiceLocator`. While the implementation uses a Service Locator, the design encourages you to write your application code using pure **Constructor Injection**, decoupling your components from the DI framework itself.

## Requirements

- Java 25+ with JDK Class-File API.

## Installation

To use Dimension-DI in your Maven project, add the following dependency to your `pom.xml`:

```XML
<dependency>
  <groupId>ru.dimension</groupId>
  <artifactId>di</artifactId>
  <version>${revision}</version>
</dependency>
```

## Usage

#### 1. Define Your Components

Create your services and components using standard `jakarta.inject.*` annotations. Your classes should not have any knowledge of Dimension-DI.

```Java
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
class Config {
  String url() { return "https://api.example.com"; }
}

class ApiClient {
  private final Config config;

  @Inject
  ApiClient(Config config) {
    this.config = config;
  }
}

class App {
  private final ApiClient api;

  @Inject
  App(ApiClient api) {
    this.api = api;
  }

  void run() {}
}
``` 

#### 2. Configure and Initialize

Scan your base packages and get entry point from ServiceLocator

```Java
import ru.dimension.di.DimensionDI;
import ru.dimension.di.ServiceLocator;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
        .scanPackages("com.example")
        .buildAndInit();

    App app = ServiceLocator.get(App.class);
    app.run();
  }
}
``` 

All dependencies are resolved via constructor injection. @Singleton classes are cached.

### Binding Interfaces and Named Implementations

When injecting interfaces, add a binding so the container knows which implementation to use.

```Java
import jakarta.inject.Inject;

interface Transport { }

class HttpTransport implements Transport {
  @Inject HttpTransport(Config cfg) { }
}

class Service {
  @Inject Service(Transport transport) { }
}
``` 

Bind interface to implementation

```Java
import ru.dimension.di.DimensionDI;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
        .scanPackages("com.example")
        .bind(Transport.class, HttpTransport.class)
        .buildAndInit();
  }
}
``` 

###  Named implementations via @Named

```Java
import jakarta.inject.Inject;
import jakarta.inject.Named;

interface Cache {}
class RedisCache implements Cache { @Inject RedisCache(Config c) { } }
class InMemoryCache implements Cache { @Inject InMemoryCache() { } }

class Repository {
  @Inject Repository(@Named("fast") Cache cache) { }
}
```

```Java
import ru.dimension.di.DimensionDI;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
        .scanPackages("com.example")
        .bindNamed(Cache.class, "fast", InMemoryCache.class)
        .bindNamed(Cache.class, "durable", RedisCache.class)
        .buildAndInit();
  }
}
``` 

### Custom Providers (like @Provides)

For objects that need custom construction logic (heavy init, load from file/env, etc)

```Java
import ru.dimension.di.DimensionDI;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
        .scanPackages("com.example")
        .provide(Config.class, ServiceLocator.singleton(() -> {
          { }
          return new Config();
        }))
        .buildAndInit();
  }
}
```

Use `ServiceLocator.singleton(supplier)` to cache the result.

```Java
import ru.dimension.di.DimensionDI;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
        .scanPackages("com.example")
        .provideNamed(Cache.class, "fast", ServiceLocator.singleton(InMemoryCache::new))
        .buildAndInit();
  }
}
```

### Skipping Scanning (Manual Wiring Only)

If you cannot or do not want to use the Class-File API

```Java
import ru.dimension.di.DimensionDI;
import ru.dimension.di.ServiceLocator;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
        .provide(Config.class, ServiceLocator.singleton(Config::new))
        .provide(ApiClient.class, () -> new ApiClient(ServiceLocator.get(Config.class)))
        .provide(App.class, () -> new App(ServiceLocator.get(ApiClient.class)))
        .buildAndInit();
  }
}
```

### Testing and Overriding

Swap implementations in tests without changing source

```Java
import ru.dimension.di.DimensionDI;
import ru.dimension.di.ServiceLocator;

class FakeApiClient extends ApiClient { }

void setupTest() {
  DimensionDI.builder()
      .scanPackages("com.example")
      .provide(ApiClient.class, FakeApiClient::new)
      .buildAndInit();
}
```

Override at runtime

```Java
@Test
void runTest() {
  ServiceLocator.override(ServiceLocator.Key.of(ApiClient.class), FakeApiClient::new);
}
```

Reset

```Java
@Test
void runTest() {
  ServiceLocator.clear();
}
```

## How It Works

### DependencyScanner

- Scans configured packages for concrete classes with:
    - an `@Inject` constructor, **or**
    - a public no-arg constructor
- Reads `@Singleton` and implemented interfaces
- Uses the JDK Class-File API to inspect bytecode without loading classes

### DimensionDI.Builder

- Builds a provider map from scanned results
- Adds manual `bind` and `provide` entries (manual overrides win)
- Initializes the ServiceLocator with providers

### ServiceLocator

- Thread-safe registry of `Key -> Supplier<?>`
- Resolves constructor parameters on-demand (supports `@Named`)
- Detects circular dependencies and throws with a helpful stack
- Caches singletons via `SingletonSupplier`
- **Utilities**: `singleton`, `override`, `alias`, `clear`

**Note**: Providers are keyed by concrete classes by default. Interfaces require explicit `bind` or `bindNamed`.

---

## Design Notes: DI vs Service Locator

- You write normal constructor-injected code with `@Inject`. This is DI-friendly.
- Internally, the container uses a simple service locator `ServiceLocator` for resolution.
- **Best practice**: only call `ServiceLocator.get(...)` at the composition root (for example, to get your top-level `App`).

---

## Limitations

- Only Jakarta Inject annotations are supported:
    - `@Inject` (constructors), `@Singleton`, `@Named` (on constructor params)
- **Not yet supported**:
    - Field or method injection
    - Custom qualifiers beyond `@Named`
    - Assisted injection, `Provider<T>`, multi-bindings (collections), scopes beyond singleton
- Scanning uses the JDK Class-File API.

---

## API Cheatsheet

### Bootstrap
- `DimensionDI.builder().scanPackages(...).bind(...).provide(...).buildAndInit();`

### Runtime Fetch (Composition Root Only)
- `ServiceLocator.get(MyRoot.class)`

### Manual Registration (on Builder)
- `.provide(type, supplier)`
- `.provideNamed(type, name, supplier)`
- `.bind(interface, impl)`
- `.bindNamed(interface, name, impl)`

### Utilities
- `ServiceLocator.singleton(supplier)` — Caches an instance.
- `ServiceLocator.override(key, supplier)` — Replaces a provider at runtime.
- `ServiceLocator.alias(aliasKey, targetKey)` — Creates an alias for a provider.
- `ServiceLocator.clear()` — Resets the entire registry.

## Comparison Tables

### Table 1: Dimension-DI vs Big Three

| Feature                       | Dimension-DI                           | Spring IoC                             | Google Guice               | Dagger 2                  |
|-------------------------------|----------------------------------------|----------------------------------------|----------------------------|---------------------------|
| Annotation Standard           | JSR-330 (Jakarta)                      | Spring-specific + JSR-330              | JSR-330                    | JSR-330 + custom          |
| Dependency Injection          | Constructor only                       | Constructor, field, method             | Constructor, field, method | Constructor-based         |
| Learning Curve                | ⭐ Minimal                              | ⭐⭐⭐⭐⭐ Steep                            | ⭐⭐⭐ Moderate               | ⭐⭐⭐ Moderate              |
| Performance                   | ⭐⭐⭐⭐⭐ Very High                        | ⭐⭐ Slow                                | ⭐⭐⭐ Medium                 | ⭐⭐⭐⭐⭐ Fastest             |
| Startup Time                  | Ultra-fast                             | Slow                                   | Fast                       | Instant (compile-time)    |
| Runtime metadata              | JDK Class-File API                     | Dynamic reflection                     | Dynamic reflection         | None (compile-time)       |
| Bytecode Generation           | None                                   | Extensive proxies                      | Extensive proxies          | Compile-time only         |
| Scoping                       | @Singleton                             | Request, Session, Singleton, Prototype | Singleton, custom          | Singleton, custom         |
| @Singleton Support            | ✅ Yes                                  | ✅ Yes                                  | ✅ Yes                      | ✅ Yes                     |
| @Named Qualifiers             | ✅ Yes                                  | ✅ Yes                                  | ✅ Yes                      | ✅ Yes                     |
| Custom Providers              | ✅ `provide()`                          | ✅ `@Bean`                              | ✅ `@Provides`              | ✅ `@Provides`             |
| Field Injection               | ❌ No                                   | ✅ Yes                                  | ✅ Yes                      | ✅ Yes (members injection) |
| Method Injection              | ❌ No                                   | ✅ Yes                                  | ✅ Yes                      | ✅ Yes (members injection) |
| Collections/Multi-bind        | ❌ No                                   | ✅ Yes                                  | ✅ Yes                      | ✅ Yes (@IntoSet/@IntoMap) |
| Circular Dependency Detection | ✅ Yes, explicit                        | ✅ Yes                                  | ✅ Yes                      | ✅ Compile-time            |
| Module/Config System          | Fluent Builder                         | `@Configuration` + XML                 | `Module` classes           | `Component` interface     |
| Testing Support               | ✅ Override, Clear                      | ✅ Profiles, Mocks                      | ✅ Binding override         | ✅ Test components         |
| JAR/Directory Scanning        | ✅ Both                                 | ✅ Both                                 | Manual by default          | N/A (compile-time)        |
| Framework Size                | ~19KB                                  | ~30MB+                                 | ~782Kb                     | ~47Kb                     |
| Best For                      | Microservices, Tools, Minimal overhead | Enterprise apps, full web stack        | Medium projects, modular   | Android, compile-safety   |
| Zero Configuration            | ✅ Full classpath scan                  | ⚠️ Needs setup                         | Manual registration        | Compile-time setup        |

---

### Table 2: Dimension-DI vs Alternative Lightweight Containers

| Feature                       | Dimension-DI                | PicoContainer            | HK2                        | Avaje Inject             |
|-------------------------------|-----------------------------|--------------------------|----------------------------|--------------------------|
| Annotation Standard           | JSR-330                     | Custom only              | JSR-330                    | JSR-330                  |
| Lightweight                   | ✅ Ultra-light               | ✅ Very light             | ⚠️ Moderate                | ✅ Light                  |
| Classpath Scanning            | ✅ Class-File API            | ❌ Manual only            | ✅ Yes                      | ✅ Yes                    |
| Constructor Injection         | ✅ Only method               | ✅ Yes                    | ✅ Yes                      | ✅ Yes                    |
| Field Injection               | ❌ No                        | ✅ Yes                    | ✅ Yes                      | ✅ Yes                    |
| Scoping                       | @Singleton                  | Singleton                | Singleton, request, custom | Singleton, custom        |
| @Named Qualifiers             | ✅ Yes                       | ❌ No                     | ✅ Yes                      | ✅ Yes                    |
| Custom Providers              | ✅ `provide()`               | ✅ Manual factories       | ✅ `@Factory`               | ✅ `@Factory`             |
| Circular Dependency Detection | ✅ Explicit error            | ❌ Runtime error          | ✅ Yes                      | ✅ Yes                    |
| Performance                   | ⭐⭐⭐⭐⭐                       | ⭐⭐⭐⭐                     | ⭐⭐⭐                        | ⭐⭐⭐⭐⭐                    |
| Startup Time                  | Ultra-fast                  | Very fast                | Fast                       | Fastest (compile-time)   |
| Runtime Reflection            | Minimal                     | Extensive                | Moderate                   | None (compile-time)      |
| Service Locator Pattern       | ✅ Internal only             | ✅ Primary model          | ✅ HK2ServiceLocator        | ✅ Internal only          |
| Compilation Model             | Runtime scan                | Manual registration      | Runtime scan               | Compile-time (APT)       |
| Maven Integration             | ✅ Easy                      | ✅ Easy                   | ✅ Easy (Jersey)            | ✅ Easy (APT)             |
| Testing Support               | ✅ Override, Clear           | ✅ Rebind                 | ✅ Yes                      | ✅ Yes                    |
| Framework Size                | ~19KB                       | ~327KB                   | ~131Kb                     | ~80KB                    |
| Active Development            | ✅ Modern                    | ⚠️ Dormant               | ✅ Active                   | ✅ Active                 |
| Jakarta Inject Ready          | ✅ Full                      | ⚠️ Partial               | ✅ Yes                      | ✅ Yes                    |
| Best For                      | Microservices, fast startup | Embedded, custom, legacy | OSGi, modular systems      | Compile-safe DI, GraalVM |
| Java Version                  | 25+                         | 8+                       | 8+                         | 11+                      |

## Documentation

| EN                             | RU                                |
|:-------------------------------|:----------------------------------|
| [README in English](README.md) | [README на русском](README-RU.md) |

## Contact
Created by [@akardapolov](mailto:akardapolov@yandex.ru)