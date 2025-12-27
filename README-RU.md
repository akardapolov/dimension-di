# Dimension-DI

Компактный, быстрый DI-фреймворк для Java с простой конфигурацией и внедрением зависимостей в runtime.

## Содержание

- [Почему Dimension-DI?](#почему-dimension-di)
- [Основная философия](#основная-философия)
- [Требования](#требования)
- [Установка](#установка)
- [Использование](#использование)
    - [1. Определите ваши компоненты](#1-определите-ваши-компоненты)
    - [2. Настройте и инициализируйте](#2-настройте-и-инициализируйте)
    - [Внедрение в члены класса: Поля и методы (Опционально)](#внедрение-в-члены-класса-поля-и-методы-опционально)
    - [Привязка интерфейсов и именованных реализаций](#привязка-интерфейсов-и-именованных-реализаций)
    - [Именованные реализации через @Named](#именованные-реализации-через-named)
    - [Пользовательские провайдеры (аналогично @Provides)](#пользовательские-провайдеры-аналогично-provides)
    - [Пропуск сканирования (только ручная настройка)](#пропуск-сканирования-только-ручная-настройка)
    - [Тестирование и переопределение](#тестирование-и-переопределение)
    - [Assisted Injection (паттерн Фабрика)](#assisted-injection-паттерн-фабрика)
    - [Коллекции и множественная привязка (Multibinding)](#коллекции-и-множественная-привязка-multibinding)
- [Как это работает](#как-это-работает)
    - [DependencyScanner](#dependencyscanner)
    - [DimensionDI.Builder](#dimensiondibuilder)
    - [ServiceLocator](#servicelocator)
- [Заметки о дизайне: DI против Service Locator](#заметки-о-дизайне-di-против-service-locator)
- [Ограничения](#ограничения)
- [Шпаргалка по API](#шпаргалка-по-api)
    - [Начальная загрузка (Bootstrap)](#начальная-загрузка-bootstrap)
    - [Получение в runtime (только в корне композиции)](#получение-в-runtime-только-в-корне-композиции)
    - [Ручная регистрация (в Builder)](#ручная-регистрация-в-builder)
    - [Коллекции / Multibinding](#коллекции--multibinding)
    - [Assisted injection](#assisted-injection)
    - [Утилиты](#утилиты)
- [Сравнительные таблицы](#сравнительные-таблицы)
- [Сравнительный анализ производительности DI-фреймворков](#сравнительный-анализ-производительности-di-фреймворков)
- [Документация](#документация)
- [Примечание](#примечание)
- [Контакты](#контакты)

Этот фреймворк обеспечивает внедрение зависимостей (DI) на основе аннотаций JSR-330 (jakarta.inject.*). Он автоматически
обнаруживает и связывает компоненты вашего приложения через внедрение в конструктор, используя сканирование classpath и
простую настройки конфигурации. Разработанный для простоты и быстрого запуска, он идеально подходит для небольших
приложений, микросервисов и инструментов, которым нужны преимущества DI без накладных расходов, связанных с более
крупными фреймворками, такими как Spring, Guice и Dagger 2.

![Схема](media/schema.png)

## Почему Dimension-DI?

Dimension-DI — это легковесный, runtime-time контейнер для внедрения зависимостей, оптимизированный для производительности и простоты использования. Он предоставляет основные функции:

- **Основан на стандартах:** Использует JSR-330 (@Inject, @Named) для чистого кода с внедрением через конструктор.
- **Мощный и безопасный:** Поддерживает scope @Singleton, обнаружение циклических зависимостей и явную привязку для
  интерфейсов.
- **Быстрый и эффективный:** Использует сканирование classpath через JDK Class-File API (без загрузки классов) для
  быстрого запуска, без проблем работая как из директорий, так и из JAR-файлов.
- **Минимальные накладные расходы**: Никаких прокси, генерации байт-кода или магии во время выполнения — только простой,
  потокобезопасный сервис-локатор "под капотом", который вам никогда не придется использовать в вашей бизнес-логике.

## Основная философия

Dimension-DI следует двухэтапному подходу:

1. **Конфигурация на этапе сборки:** Гибкий API `Builder` используется для настройки DI-контейнера. Этот этап включает
   сканирование classpath на наличие компонентов, помеченных `@Inject`, анализ их зависимостей и регистрацию
   провайдеров (рецептов для создания объектов). Именно здесь проявляется "DI" часть.
2. **Разрешение во время выполнения:** Во время выполнения зависимости разрешаются с помощью внутреннего, глобально
   доступного `ServiceLocator`. Хотя реализация использует Service Locator, дизайн поощряет написание кода вашего
   приложения с использованием чистого **Внедрения через конструктор (Constructor Injection)**, отделяя ваши компоненты
   от самого DI-фреймворка.

## Требования

- Java 25+ с JDK Class-File API.

## Установка

Чтобы использовать Dimension-DI в вашем Maven-проекте, добавьте следующую зависимость в ваш `pom.xml`:

```XML

<dependency>
  <groupId>ru.dimension</groupId>
  <artifactId>di</artifactId>
  <version>${revision}</version>
</dependency>
```

## Использование

### 1. Определите ваши компоненты

Создайте ваши сервисы и компоненты, используя стандартные аннотации `jakarta.inject.*`. Ваши классы не должны ничего
знать о Dimension-DI.

```Java
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
class Config {

  String url() {
    return "https://api.example.com";
  }
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

  void run() {
  }
}
```

### 2. Настройте и инициализируйте

Просканируйте ваши базовые пакеты и получите точку входа из ServiceLocator.

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

Все зависимости разрешаются через внедрение в конструктор. Классы с аннотацией @Singleton кэшируются.

### Внедрение в члены класса: Поля и методы (Опционально)

Хотя внедрение через конструктор настоятельно рекомендуется для обязательных зависимостей, Dimension-DI также поддерживает внедрение в поля и методы. Это полезно для опциональных зависимостей или для интеграции с фреймворками, которые этого требуют.

**Внедрение в поля**

Аннотируйте не-финальное поле с помощью `@Inject`. Контейнер установит его значение после создания объекта.

```Java
import jakarta.inject.Inject;
import jakarta.inject.Named;

class NotificationService {
  @Inject private EmailSender emailSender;

  @Inject @Named("sms")
  private MessageSender smsSender;

  public void notify(User user, String message) {
    emailSender.send(user.getEmail(), message);
  }
}
```

**Внедрение в методы**

Аннотируйте метод с помощью @Inject. Контейнер разрешит его параметры и вызовет его после создания объекта.

```Java
class MyService {
  private DependencyA depA;

  // Must have an injectable constructor (e.g., public no-arg)
  public MyService() {}

  @Inject
  public void initialize(DependencyA depA) {
    // This method is called by the container after construction
    this.depA = depA;
  }
}
``` 

Примечание: Внедрение в члены класса (как в поля, так и в методы) происходит после вызова конструктора. Порядок внедрения между полями и методами не гарантируется. Финальные (final) поля не могут быть внедрены.

### Привязка интерфейсов и именованных реализаций

При внедрении интерфейсов добавьте привязку, чтобы контейнер знал, какую реализацию использовать.

```Java
import jakarta.inject.Inject;

interface Transport {

}

class HttpTransport implements Transport {

  @Inject
  HttpTransport(Config cfg) {
  }
}

class Service {

  @Inject
  Service(Transport transport) {
  }
}
```

Привяжите интерфейс к реализации.

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

### Именованные реализации через @Named

```Java
import jakarta.inject.Inject;
import jakarta.inject.Named;

interface Cache {

}

class RedisCache implements Cache {

  @Inject
  RedisCache(Config c) {
  }
}

class InMemoryCache implements Cache {

  @Inject
  InMemoryCache() {
  }
}

class Repository {

  @Inject
  Repository(@Named("fast") Cache cache) {
  }
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

### Пользовательские провайдеры (аналогично @Provides)

Для объектов, которым требуется пользовательская логика создания (тяжелая инициализация, загрузка из файла/окружения и
т.д.)

```Java
import ru.dimension.di.DimensionDI;

public class Main {

  public static void main(String[] args) {
    DimensionDI.builder()
        .scanPackages("com.example")
        .provide(Config.class, ServiceLocator.singleton(() -> {
          {
          }
          return new Config();
        }))
        .buildAndInit();
  }
}
```

Используйте `ServiceLocator.singleton(supplier)` для кэширования результата.

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

### Пропуск сканирования (только ручная настройка)

Если вы не можете или не хотите использовать Class-File API.

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

### Тестирование и переопределение

Заменяйте реализации в тестах, не изменяя исходный код.

```Java
import ru.dimension.di.DimensionDI;
import ru.dimension.di.ServiceLocator;

class FakeApiClient extends ApiClient {

}

void setupTest() {
  DimensionDI.builder()
      .scanPackages("com.example")
      .provide(ApiClient.class, FakeApiClient::new)
      .buildAndInit();
}
```

Переопределение во время выполнения.

```Java

@Test
void runTest() {
  ServiceLocator.override(ServiceLocator.Key.of(ApiClient.class), FakeApiClient::new);
}
```

Сброс.

```Java

@Test
void runTest() {
  ServiceLocator.clear();
}
```

### Assisted Injection (паттерн Фабрика)

Когда вам нужно создавать объекты, которые требуют как зависимостей из DI-контейнера, так и параметров времени выполнения, используйте Assisted Injection. Этот паттерн использует интерфейсы фабрик для комбинирования внедряемых синглтонов со значениями, предоставляемыми вызывающим кодом.

#### Определите компонент с параметрами @Assisted

```java
import jakarta.inject.Inject;
import ru.dimension.di.Assisted;

class UserSession {
    private final String sessionId;
    private final int timeout;
    private final SessionManager manager;  // from DI
    private final Logger logger;           // from DI

    @Inject
    UserSession(@Assisted String sessionId,
                @Assisted int timeout,
                SessionManager manager,
                Logger logger) {
        this.sessionId = sessionId;
        this.timeout = timeout;
        this.manager = manager;
        this.logger = logger;
    }
}
```

#### Создайте интерфейс фабрики
```java
interface UserSessionFactory {
    UserSession create(String sessionId, int timeout);
}
```

#### Зарегистрируйте и используйте фабрику
```java
import ru.dimension.di.Assisted;
import ru.dimension.di.DimensionDI;
import ru.dimension.di.ServiceLocator;
import jakarta.inject.Inject;

public class Main {
  public static void main(String[] args) {
    DimensionDI.builder()
            .scanPackages("com.example")
            // Регистрируем фабрику и связываем её с реализацией UserSession
            .bindFactory(UserSessionFactory.class, UserSession.class)
            .buildAndInit();

    // Получаем фабрику из DI
    UserSessionFactory factory = ServiceLocator.get(UserSessionFactory.class);

    // Создаем экземпляры с runtime-параметрами
    UserSession session1 = factory.create("sess-001", 3600);
    UserSession session2 = factory.create("sess-002", 7200);
  }
}
```

#### Прямое создание (без интерфейса фабрики)

Для простых случаев создавайте экземпляры напрямую без определения интерфейса фабрики:

```java
UserSession session = ServiceLocator.create(UserSession.class, "sess-001", 3600);
```

##### Именованные параметры Assisted

Когда у вас есть несколько параметров одного типа, используйте именованные аннотации @Assisted:

```java
class Connection {
    @Inject
    Connection(@Assisted("host") String host,
               @Assisted("backup") String backupHost,
               ConnectionPool pool) {
        // ...
    }
}

interface ConnectionFactory {
    Connection create(@Assisted("host") String host,
                      @Assisted("backup") String backupHost);
}
```

Примечание: Интерфейсы фабрик по умолчанию являются синглтонами. Каждый вызов фабрики создаёт новый экземпляр целевого класса, при этом DI-зависимости (такие как @Singleton) правильно разделяются между всеми созданными экземплярами.

### Коллекции и множественная привязка (Multibinding)

Dimension-DI поддерживает внедрение коллекций привязок (`List<T>`, `Set<T>` и `Map<String, T>`). Есть два способа использовать эту функцию: **Неявный** (на основе сканирования) и **Явный** (в стиле Dagger).

#### 1. Неявный Multibinding (Автоматический)

При сканировании пакетов реализации, обнаруженные для интерфейса, автоматически становятся доступными для внедрения коллекций.
*   `List<T>` / `Set<T>`: Содержит все обнаруженные реализации.
*   `Map<String, T>`: Содержит только именованные привязки, с ключами в виде имени реализации (или значения `@Named`).

**Пример:**

```java
public interface Plugin {}
@Singleton public class AudioPlugin implements Plugin {}
public class VideoPlugin implements Plugin {}
```

```java
public class PluginManager {
    @Inject
    public PluginManager(List<Plugin> allPlugins, Map<String, Plugin> pluginMap) {
        // allPlugins содержит [AudioPlugin, VideoPlugin]
        // ключи pluginMap: "AudioPlugin", "VideoPlugin"
    }
}
```

#### 2. Явный Multibinding (В стиле Dagger)

Вы можете явно вносить элементы, используя `intoSet` или `intoMap` в `Builder`.
**Примечание:** Если вы регистрируете явные вклады для типа, **неявное сканирование для этой коллекции отключается**. Будут внедрены только ваши явные записи.

```java
public static void buildAndInit() {
  DimensionDI.builder()
      .scanPackages("com.example")
      .intoSet(Plugin.class, () -> new CustomPlugin())
      .intoSetSingleton(Plugin.class, () -> new HeavyPlugin())
      .intoMap(Handler.class, "login", () -> new LoginHandler())
      .buildAndInit();
}
```

#### 3. Именованное внедрение одного элемента

Вы можете использовать `@Named` в точке внедрения коллекции, чтобы внедрить коллекцию, содержащую **только этот конкретный именованный бин**.

```java
public class Service {
    // Внедряет List, содержащий ТОЛЬКО бин с именем "special"
    @Inject
    public Service(@Named("special") List<Plugin> specialPlugins) {
       // size() == 1
    }
}
```

#### Сводка и приоритеты

| Тип внедрения        | Поведение (Автоматическое)                          | Поведение (Явное через Builder)                   |
|:---------------------|:----------------------------------------------------|:--------------------------------------------------|
| **`List<T>`**        | Все отсканированные/зарегистрированные экземпляры T | Все вклады `intoSet(T, ...)`                      |
| **`Set<T>`**         | Все отсканированные/зарегистрированные экземпляры T | Все вклады `intoSet(T, ...)`                      |
| **`Map<String, T>`** | Ключи = Простое имя класса (или @Named)             | Ключи = Явные строки, переданные в `intoMap(...)` |

Помимо внедрения, вы можете запрашивать multibindings напрямую через `ServiceLocator.getAll(T.class)` или `ServiceLocator.getNamedMap(T.class)`.

## Как это работает

### DependencyScanner

- Сканирует настроенные пакеты в поиске конкретных классов с:
    - конструктором с аннотацией `@Inject`, **или**
    - публичным конструктором без аргументов
- Считывает аннотацию `@Singleton` и реализованные интерфейсы
- Использует JDK Class-File API для анализа байт-кода без загрузки классов

### DimensionDI.Builder

- Создает карту провайдеров из результатов сканирования
- Добавляет ручные записи `bind` и `provide` (ручные переопределения имеют приоритет)
- Инициализирует ServiceLocator с провайдерами

### ServiceLocator

- Потокобезопасный реестр `Key -> Supplier<?>`
- Разрешает параметры конструктора по запросу (поддерживает `@Named`)
- **Умный резервный поиск:**
  - Именованные запросы откатываются к неименованным привязкам, когда именованная не найдена
  - Неименованные запросы разрешаются к уникальным именованным привязкам, когда неименованная не существует
  - Ручные привязки переопределяют сканированные при авто-алиасинге
  - Полезные сообщения об ошибках показывают доступные привязки при неудачном разрешении
- Выполняет внедрение в члены (поля и методы) после конструктора
- Поддерживает `@Inject` на полях и методах, включая квалификаторы `@Named`
- Обнаруживает циклические зависимости и выбрасывает исключение с полезным стеком
- Кеширует синглтоны через `SingletonSupplier`

**Примечание**: Поведение резервного поиска можно отключить для более строгой семантики DI, используя `setNamedFallbackEnabled(false)` и `setUnnamedFallbackEnabled(false)`.

---

## Заметки о дизайне: DI против Service Locator

- Вы пишете обычный код с внедрением через конструктор с помощью `@Inject`. Это соответствует принципам DI.
- Внутренне контейнер использует простой сервис-локатор `ServiceLocator` для разрешения зависимостей.
- **Лучшая практика**: вызывайте `ServiceLocator.get(...)` только в корне композиции (composition root), например, для
  получения вашего `App` верхнего уровня.

---

## Ограничения

- Поддерживаются только аннотации Jakarta Inject:
    - `@Inject` (на конструкторах, полях и методах)
    - `@Singleton`
    - `@Named` (на параметрах конструктора, полях и параметрах методов)
- Внедрение в поля работает с `private`, `protected` и `public` не-final полями, включая унаследованные.
- Fallback именованных/неименованных зависимостей включен по умолчанию для удобства; отключите для строгого режима.
- Ручные привязки переопределяют отсканированные при включенном авто-алиасинге.
- **Пока не поддерживается**:
    - Пользовательские квалификаторы, кроме `@Named`
    - Внедрение `Provider<T>`
    - Scope, отличные от singleton
- Сканирование использует JDK Class-File API (Java 24+).

---

## Шпаргалка по API

### Начальная загрузка (Bootstrap)

- `DimensionDI.builder().scanPackages(...).bind(...).provide(...).buildAndInit();`

### Получение в runtime (только в корне композиции)

- `ServiceLocator.get(MyRoot.class)`

### Ручная регистрация (в Builder)
- `.provide(type, supplier)` — Регистрирует кастомный провайдер для типа
- `.provideNamed(type, name, supplier)` — Регистрирует провайдер для именованного типа
- `.provideSingleton(type, supplier)` — Регистрирует синглтон-провайдер
- `.bind(interface, impl)` — Привязывает интерфейс к реализации
- `.bindNamed(interface, name, impl)` — Привязывает именованный интерфейс к реализации
- `.bindFactory(factoryInterface, targetClass)` — Регистрирует фабрику для assisted injection
- `.bindFactory(factoryInterface)` — Регистрирует фабрику (целевой класс выводится из возвращаемого типа)
- `.autoAliasUniqueNamed(boolean)` — Включить/отключить авто-алиасинг для уникальных именованных привязок (по умолчанию: true)

### Коллекции / Multibinding
- `.intoSet(type, supplier)` — Вносит элемент в `Set<T>` / `List<T>`
- `.intoMap(type, key, supplier)` — Вносит запись в `Map<String, T>`
- `.intoSetSingleton(...)` / `.intoMapSingleton(...)` — Вносит кэшированный синглтон
- `ServiceLocator.getAll(type)` — Возвращает `List<T>` всех привязок
- `ServiceLocator.getNamedMap(type)` — Возвращает `Map<String, T>` именованных привязок

### Assisted Injection
- `ServiceLocator.create(type, assistedArgs...)` — Создаёт экземпляр с параметрами времени выполнения
- `ServiceLocator.createFactory(factoryInterface, targetClass)` — Создаёт реализацию фабрики
- `@Assisted` — Помечает параметр конструктора как предоставляемый во время выполнения
- `@Assisted("name")` — Именованный assisted-параметр для разрешения неоднозначности

### Утилиты
- `ServiceLocator.singleton(supplier)` — Кеширует экземпляр.
- `ServiceLocator.override(key, supplier)` — Заменяет провайдер во время выполнения.
- `ServiceLocator.alias(aliasKey, targetKey)` — Создаёт алиас для провайдера.
- `ServiceLocator.clear()` — Сбрасывает весь реестр.
- `ServiceLocator.setNamedFallbackEnabled(boolean)` — Включить/отключить резерв именованная→неименованная (по умолчанию: true).
- `ServiceLocator.setUnnamedFallbackEnabled(boolean)` — Включить/отключить резерв неименованная→именованная (по умолчанию: true).

## Сравнительные таблицы

### Таблица 1. Dimension-DI против «Большой тройки»

| Возможность                  | Dimension-DI                         | Spring IoC                             | Google Guice                | Dagger 2                  |
|------------------------------|--------------------------------------|----------------------------------------|-----------------------------|---------------------------|
| Стандарт аннотаций           | JSR-330 (Jakarta)                    | Spring-specific + JSR-330              | JSR-330                     | JSR-330 + кастомные       |
| Тип внедрения зависимостей   | Конструктор, поле, метод             | Конструктор, поле, метод               | Конструктор, поле, метод    | На основе конструктора    |
| Кривая обучения              | ⭐ Минимальная                        | ⭐⭐⭐⭐⭐ Крутая                           | ⭐⭐⭐ Умеренная               | ⭐⭐⭐ Умеренная             |
| Производительность           | ⭐⭐⭐⭐⭐ Очень высокая                  | ⭐⭐ Низкая                              | ⭐⭐⭐ Средняя                 | ⭐⭐⭐⭐⭐ Высочайшая          |
| Время запуска                | Сверхбыстрое                         | Медленное                              | Быстрое                     | Мгновенное (compile-time) |
| Метаданные в runtime         | JDK Class-File API                   | Динамическая рефлексия                 | Динамическая рефлексия      | Нет (compile-time)        |
| Генерация байт-кода          | Нет                                  | Обширные прокси                        | Обширные прокси             | Только в compile-time     |
| Области видимости (Scoping)  | @Singleton                           | Request, Session, Singleton, Prototype | Singleton, кастомные        | Singleton, кастомные      |
| Поддержка @Singleton         | ✅ Да                                 | ✅ Да                                   | ✅ Да                        | ✅ Да                      |
| Квалификаторы @Named         | ✅ Да                                 | ✅ Да                                   | ✅ Да                        | ✅ Да                      |
| Пользовательские провайдеры  | ✅ `provide()`                        | ✅ `@Bean`                              | ✅ `@Provides`               | ✅ `@Provides`             |
| Assisted внедрение           | ✅ `@Assisted` + Фабрика              | ❌ Вручную                              | ✅ AssistedInject            | ✅ @AssistedInject         |
| Внедрение в поля             | ✅ Да                                 | ✅ Да                                   | ✅ Да                        | ✅ Да (members injection)  |
| Внедрение в методы           | ✅ Да                                 | ✅ Да                                   | ✅ Да                        | ✅ Да (members injection)  |
| Коллекции/Мульти-биндинги    | ✅ Да (явно и неявно)                 | ✅ Да                                   | ✅ Да                        | ✅ Да (@IntoSet/@IntoMap)  |
| Fallback: по имени/стандарт  | ✅ Автоматический                     | ❌ Нет                                  | ❌ Нет                       | ❌ Нет                     |
| Циклические зависимости      | ✅ Да, явная ошибка                   | ✅ Да                                   | ✅ Да                        | ✅ Compile-time            |
| Система модулей/конфигурации | Fluent Builder                       | `@Configuration` + XML                 | Классы `Module`             | Интерфейс `Component`     |
| Поддержка тестирования       | ✅ Override, Clear                    | ✅ Профили, Моки                        | ✅ Переопределение биндингов | ✅ Тестовые компоненты     |
| Сканирование JAR/директорий  | ✅ Оба                                | ✅ Оба                                  | Вручную по умолчанию        | N/A (compile-time)        |
| Размер фреймворка            | ~19KB                                | ~10MB+                                 | ~782Kb                      | ~47Kb                     |
| Лучше всего для              | Микросервисы, утилиты, мин. издержки | Enterprise-приложения, полный веб-стек | Средние проекты, модульные  | Android, compile-safety   |
| Нулевая конфигурация         | ✅ Полное сканирование classpath      | ⚠️ Требует настройки                   | Ручная регистрация          | Настройка в compile-time  |

---

### Таблица 2. Dimension-DI против альтернативных легковесных контейнеров

| Возможность                  | Dimension-DI                | PicoContainer        | HK2                        | Avaje Inject              |
|------------------------------|-----------------------------|----------------------|----------------------------|---------------------------|
| Стандарт аннотаций           | JSR-330                     | Только свои          | JSR-330                    | JSR-330                   |
| Легковесность                | ✅ Ультра-легкий             | ✅ Очень легкий       | ⚠️ Умеренный               | ✅ Легкий                  |
| Сканирование Classpath       | ✅ Class-File API            | ❌ Только вручную     | ✅ Да                       | ✅ Да                      |
| Внедрение через конструктор  | ✅ Да                        | ✅ Да                 | ✅ Да                       | ✅ Да                      |
| Внедрение в поля             | ✅ Да                        | ✅ Да                 | ✅ Да                       | ✅ Да                      |
| Внедрение в метод            | ✅ Да                        | ✅ Да                 | ✅ Да                       | ✅ Да                      |
| Области видимости (Scoping)  | @Singleton                  | Singleton            | Singleton, request, custom | Singleton, custom         |
| Квалификаторы @Named         | ✅ Да                        | ❌ Нет                | ✅ Да                       | ✅ Да                      |
| Пользовательские провайдеры  | ✅ `provide()`               | ✅ Ручные фабрики     | ✅ `@Factory`               | ✅ `@Factory`              |
| Обнаружение циклич. зависим. | ✅ Явная ошибка              | ❌ Ошибка в рантайме  | ✅ Да                       | ✅ Да                      |
| Производительность           | ⭐⭐⭐⭐⭐                       | ⭐⭐⭐⭐                 | ⭐⭐⭐                        | ⭐⭐⭐⭐⭐                     |
| Время запуска                | Сверхбыстрое                | Очень быстрое        | Быстрое                    | Быстрейшее (compile-time) |
| Рефлексия в рантайме         | Минимальная                 | Обширная             | Умеренная                  | Нет (compile-time)        |
| Паттерн Service Locator      | ✅ Только внутренний         | ✅ Основная модель    | ✅ HK2ServiceLocator        | ✅ Только внутренний       |
| Модель компиляции            | Скан в рантайме             | Ручная регистрация   | Скан в рантайме            | Compile-time (APT)        |
| Интеграция с Maven           | ✅ Простая                   | ✅ Простая            | ✅ Простая (Jersey)         | ✅ Простая (APT)           |
| Поддержка тестирования       | ✅ Override, Clear           | ✅ Rebind             | ✅ Да                       | ✅ Да                      |
| Размер фреймворка            | ~19KB                       | ~327KB               | ~131Kb                     | ~80KB                     |
| Активная разработка          | ✅ Современная               | ⚠️ В застое          | ✅ Активная                 | ✅ Активная                |
| Готовность к Jakarta Inject  | ✅ Полная                    | ⚠️ Частичная         | ✅ Да                       | ✅ Да                      |
| Лучше всего для              | Микросервисы, быстрый старт | Встраиваемые, legacy | OSGi, модульные системы    | Compile-safe DI, GraalVM  |
| Версия Java                  | 25+                         | 8+                   | 8+                         | 11+                       |

---

## Сравнительный анализ производительности DI-фреймворков

Используется исходный код проекта [DI Containers Comparison](https://github.com/Heapy/di-comparison).

### Тестовое окружение
```
Processor: AMD Ryzen 5 5600H with Radeon Graphics, 3301 Mhz, 6 Core(s), 12 Logical Processor(s)
Memory: 16.0 GB
Disk: Generic Flash Disk USB Device<br>- SAMSUNG MZVL2512HCJQ-00B00 (476.94 GB) 
OS: Microsoft Windows 11 (WSL2)

java version "25.0.1" 2025-10-21 LTS
Java(TM) SE Runtime Environment (build 25.0.1+8-LTS-27)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.1+8-LTS-27, mixed mode, sharing)
```

### Таблица 3. Результаты JVM
| DI  | Jar w/Deps Size, Mb | ⬇️ Wall, ms | User, ms | Sys, ms | Max RSS, Mb | Allocated, Mb | Alloc Count | LoC |
|-----|---------------------|-------------|----------|---------|-------------|---------------|-------------|-----|
| jvm | 1.75                | 79.30       | 23.20    | 18.40   | 41.38       | 0.25          | 6           | 2   |

### Таблица 4. Результаты для 2 классов
| DI                   | Jar w/Deps Size, Mb | ⬇️ Wall, ms   | User, ms      | Sys, ms      | Max RSS, Mb  | Allocated, Mb | Alloc Count | LoC       |
|----------------------|---------------------|---------------|---------------|--------------|--------------|---------------|-------------|-----------|
| baseline             | 1.75                | 100.30        | 47.00         | 22.10        | 43.54        | 0.25          | 6           | 24        |
| kotlin-lazy          | 1.75                | 101.70        | 53.30         | 20.90        | 43.73        | 0.25          | 7           | 31        |
| dagger               | 1.82                | 157.40        | 59.80         | 27.40        | 43.73        | 0.26          | 8           | 51        |
| cayennedi            | 1.82                | 160.50        | 119.00        | 30.60        | 51.27        | 0.25          | 8           | 49        |
| <b>dimension</b>     | <b>1.78</b>         | <b>178.70</b> | <b>136.50</b> | <b>35.90</b> | <b>54.57</b> | <b>0.28</b>   | <b>12</b>   | <b>37</b> |
| kodein               | 3.43                | 248.30        | 173.10        | 42.70        | 53.92        | 2.41          | 33          | 32        |
| koin                 | 2.22                | 283.90        | 180.90        | 49.80        | 58.18        | 2.15          | 38          | 31        |
| koin-reflect         | 2.22                | 288.70        | 183.70        | 50.40        | 56.79        | 2.32          | 39          | 33        |
| komok-to-be-injected | 2.48                | 296.00        | 157.90        | 49.80        | 53.41        | 2.56          | 34          | 33        |
| bootique             | 4.69                | 426.40        | 403.20        | 65.50        | 68.51        | 0.41          | 37          | 63        |
| spring-xml           | 6.78                | 574.70        | 535.40        | 81.50        | 74.79        | 0.67          | 66          | 37        |
| guice                | 5.62                | 620.00        | 489.70        | 88.70        | 69.18        | 0.62          | 60          | 47        |
| spring               | 6.78                | 653.60        | 601.70        | 94.30        | 71.25        | 1.17          | 92          | 38        |
| spring-index         | 6.78                | 669.70        | 652.60        | 90.20        | 74.07        | 0.95          | 100         | 34        |
| spring-scan          | 6.78                | 704.20        | 666.50        | 115.10       | 73.60        | 1.05          | 96          | 34        |
| owb                  | 3.30                | 712.70        | 702.20        | 98.00        | 74.61        | 0.74          | 75          | 49        |
| springboot           | 10.33               | 1,845.00      | 2,346.50      | 215.60       | 110.21       | 2.45          | 355         | 56        |
| springboot-index     | 10.33               | 1,850.70      | 2,259.60      | 228.50       | 109.98       | 2.18          | 336         | 45        |

### Таблица 5. Результаты для 100 классов
| DI                        | Jar w/Deps Size, Mb | ⬇️ Wall, ms   | User, ms      | Sys, ms      | Max RSS, Mb  | Allocated, Mb | Alloc Count | LoC        |
|---------------------------|---------------------|---------------|---------------|--------------|--------------|---------------|-------------|------------|
| baseline-deep             | 1.88                | 175.70        | 110.80        | 32.90        | 44.27        | 0.25          | 11          | 719        |
| kotlin-lazy-deep          | 1.89                | 254.00        | 240.60        | 45.30        | 56.30        | 0.39          | 26          | 925        |
| dagger-deep               | 2.05                | 278.90        | 180.40        | 44.40        | 47.96        | 0.26          | 14          | 1145       |
| cayennedi-deep            | 2.00                | 305.00        | 308.80        | 53.10        | 58.54        | 0.27          | 19          | 1953       |
| <b>dimension-deep</b>     | <b>1.91</b>         | <b>376.60</b> | <b>464.80</b> | <b>53.00</b> | <b>67.68</b> | <b>0.63</b>   | <b>46</b>   | <b>831</b> |
| koin-deep                 | 2.37                | 418.20        | 371.00        | 65.10        | 67.83        | 2.68          | 57          | 725        |
| komok-to-be-injected-deep | 2.62                | 456.20        | 358.30        | 65.20        | 67.63        | 2.83          | 57          | 927        |
| koin-reflect-deep         | 2.52                | 478.30        | 349.90        | 74.80        | 61.65        | 2.75          | 55          | 727        |
| kodein-deep               | 3.86                | 480.30        | 424.40        | 75.30        | 69.57        | 1.92          | 59          | 726        |
| bootique-deep             | 4.82                | 517.80        | 511.50        | 78.20        | 69.98        | 0.42          | 42          | 1057       |
| guice-deep                | 5.75                | 689.80        | 628.50        | 104.40       | 74.25        | 0.64          | 74          | 1141       |
| spring-xml-deep           | 6.90                | 868.60        | 843.30        | 118.60       | 75.45        | 1.24          | 94          | 931        |
| spring-deep               | 6.91                | 1,066.80      | 1,189.30      | 140.30       | 75.31        | 0.98          | 123         | 1032       |
| owb-deep                  | 3.42                | 1,130.60      | 1,391.70      | 147.00       | 78.70        | 1.04          | 137         | 1143       |
| spring-index-deep         | 6.91                | 1,187.30      | 1,521.40      | 141.90       | 81.28        | 1.67          | 213         | 729        |
| spring-scan-deep          | 6.91                | 1,361.00      | 1,719.00      | 168.00       | 81.69        | 1.68          | 218         | 728        |
| springboot-index-deep     | 10.46               | 2,234.70      | 3,027.00      | 247.60       | 112.44       | 2.99          | 451         | 739        |
| springboot-deep           | 10.46               | 2,558.00      | 3,515.90      | 275.90       | 115.62       | 2.91          | 464         | 1050       |

## Документация

| EN                                | RU                                |
|:----------------------------------|:----------------------------------|
| [README на английском](README.md) | [README на русском](README-RU.md) |

## Примечание

Раздел [Сравнительный анализ производительности DI-фреймворков](#сравнительный-анализ-производительности-di-фреймворков) создан с использованием:

*   **Проект**: [DI Containers Comparison](https://github.com/Heapy/di-comparison)
*   **Автор**: [Ruslan Ibrahimau (Ibragimov)](https://github.com/IRus)
*   **Лицензия**: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)

## Контакты

Создано [@akardapolov](mailto:akardapolov@yandex.ru)