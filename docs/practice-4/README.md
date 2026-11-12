# Практика 4 — Unit-тесты

Автор: Константин Каренин. Обновлено: 12.11.2026.

Основание: [чек-лист артефакта 4](../checklists.md#артефакт-4--unit-тесты-на-junit-5),
[ТЗ Gateway](../../tz/02-gateway.md), [Switch](../../tz/03-switch.md),
[Authorization](../../tz/04-authorization.md) и [Card Management](../../tz/05-card-management.md).
Это модуль 4 из README проекта; прежняя таблица сдачи относила unit-тесты к практике 3.

## Состав работы

Тесты используют JUnit Jupiter, параметризацию и AssertJ из `spring-boot-starter-test`.
Mockito заменяет репозитории, HTTP-вызовы, публикацию событий и цепочку Gateway.
Spring-контекст, HTTP-серверы, PostgreSQL, RabbitMQ и Docker не запускаются.
Производственный код сервисов не изменён.

| Сервис / требования | Тесты | Основные проверки |
|---|---|---|
| Gateway, G-01 | [TransactionRequestValidatorTest](../../services/gateway/src/test/java/com/processing/gateway/validation/TransactionRequestValidatorTest.java) | Обязательные поля, форматы, `amount=null/-0.01/0/0.01`, отсутствие issuer до маршрутизации |
| Gateway, G-02 | [InMemoryRateLimiterTest](../../services/gateway/src/test/java/com/processing/gateway/ratelimit/InMemoryRateLimiterTest.java) | 100 разрешений и отказ на 101-м, независимость клиентов, граница 10 мс, предел накопления токенов и истечение bucket |
| Gateway, G-02 | [InMemoryCircuitBreakerTest](../../services/gateway/src/test/java/com/processing/gateway/circuitbreaker/InMemoryCircuitBreakerTest.java) | Порог трёх ошибок, граница 10 с, одна конкурентная probe-попытка, успех/ошибка/отмена probe |
| Gateway, G-02 | [TransactionRateLimitFilterTest](../../services/gateway/src/test/java/com/processing/gateway/ratelimit/TransactionRateLimitFilterTest.java) | Ответ 429 с `Retry-After`, JSON ошибки, остановка цепочки и пропуск других маршрутов |
| Switch, S-01 | [RoutingServiceTest](../../services/switch/src/test/java/com/processing/service/RoutingServiceTest.java), [AuthorizationRequestNormalizerTest](../../services/switch/src/test/java/com/processing/service/AuthorizationRequestNormalizerTest.java) | Известный/неизвестный BIN, короткий PAN, копирование конфигурации, границы длины идентификаторов, идемпотентность нормализации |
| Switch, S-02 | [RouteServiceTest](../../services/switch/src/test/java/com/processing/service/RouteServiceTest.java) | Передача issuer и комиссии, содержимое публикации, отказ Authorization, rollback только для APPROVED при сбое Logger |
| Switch | [AuthorizationClientTest](../../services/switch/src/test/java/com/processing/service/AuthorizationClientTest.java) | Ответы APPROVED/DECLINED, пустое тело и неверный статус, восстановление после транспортной ошибки, исчерпание retry, открытый circuit breaker, ответы и сбои rollback, health-check |
| Authorization | [AuthServiceImplTest](../../services/authorization/src/test/java/com/processing/authorization/services/AuthServiceImplTest.java) | Статусы, срок, граница баланса, решение репозитория лимитов, однократный retry конфликта, коды ошибок чтения/резерва/возврата, отсутствие ложного события успеха |
| Authorization, A-12 | [RrnAllocationTest](../../services/authorization/src/test/java/com/processing/authorization/services/RrnAllocationTest.java) | 1001 уникальный RRN, переход блока на 1001-м вызове, ровно два обращения за блоком |
| Card Management, C-12 | [CardMoneyRulesTest](../../services/card-management/src/test/java/com/processing/cardmanagement/models/CardMoneyRulesTest.java) | Классы статусов, границы баланса и лимитов, неизменность исходной карты, принадлежность резерва/возврата, запрет повторного возврата |
| Card Management | [CardServiceImplTest](../../services/card-management/src/test/java/com/processing/cardmanagement/services/CardServiceImplTest.java) | Порядок reserve, возврат, дубликаты и отсутствующие сущности, PATCH с null/нулём, пагинация, массовые изменения, создание и fallback при коллизии |
| Card Management | [LuhnValidatorTest](../../services/card-management/src/test/java/com/processing/cardmanagement/services/LuhnValidatorTest.java), [RetryServiceImplTest](../../services/card-management/src/test/java/com/processing/cardmanagement/services/retries/RetryServiceImplTest.java) | Известные контрольные цифры, генерация по пяти BIN, границы числа повторов, сохранение причины ошибки |

Обозначения G-01/G-02/S-01/S-02/C-12/A-12 взяты из [практики 3](../practice-3-pyramid/test-cases.md).
Все шесть unit-сценариев P3-01…P3-06 автоматизированы соответствующими тестами таблицы.
Другие уровни пирамиды в этой работе не реализуются.

## Воспроизведение

Требуются JDK 21 и Maven 3.9+. Команды выполняются из корня репозитория.
Первый запуск скачивает зависимости из Maven Central.

```bat
mvn -B -f services/pom.xml -Pgateway,switch,authorization,card-management -pl gateway,switch,authorization,card-management -am test
```

Полная локальная проверка со сборкой, Checkstyle (включая тесты) и отчётами JaCoCo:

```bat
mvn -B -f services/pom.xml -Pgateway,switch,authorization,card-management -pl gateway,switch,authorization,card-management -am clean verify
```

Один сервис вместе с `common`, например:

```bat
mvn -B -f services/pom.xml -Pauthorization test
```

Surefire сохраняет результаты в `services/{service}/target/surefire-reports/`.
После `verify` HTML-отчёт покрытия находится в `services/{service}/target/site/jacoco/index.html`,
машинные данные — в `jacoco.xml` и `jacoco.csv` рядом с ним.
Сгенерированные файлы `target/` не входят в сдаваемый исходный код.

По условию практики для **всего модуля Switch** установлен порог покрытия ветвей **≥ 70%**.
В [POM Switch](../../services/switch/pom.xml) цель JaCoCo `check` привязана к `verify`:
`BUNDLE / BRANCH / COVEREDRATIO / 0.70`. Исключений классов и пакетов из покрытия нет.
Команда `test` выполняет тесты, а `verify` (также `install` в CI) дополнительно проверяет порог.

## Результаты локальной проверки

12.11.2026 выполнена чистая сборка `clean verify` на OpenJDK 21.0.1 и Maven 3.9.11:

| Сервис | Тестовых классов | Выполнений тестов | Failures / Errors / Skipped |
|---|---:|---:|---|
| Gateway | 4 | 61 | 0 / 0 / 0 |
| Switch | 4 | 50 | 0 / 0 / 0 |
| Authorization | 2 | 41 | 0 / 0 / 0 |
| Card Management | 4 | 59 | 0 / 0 / 0 |
| **Всего** | **14** | **211** | **0 / 0 / 0** |

Итог — `BUILD SUCCESS`; Checkstyle не нашёл нарушений в `common` и четырёх целевых сервисах.
JaCoCo Quality Gate Switch пройден: **56 из 72 ветвей, 77,8%**.
Дополнительно проверен отказ порога: чистый `verify` только с прежними тремя тестовыми классами
Switch завершается ошибкой JaCoCo при покрытии **41 из 72 ветвей, 56,9%**.
В итоговом отчёте после полного прогона учтены все четыре тестовых класса Switch.

Покрытие целевых классов по JaCoCo, в формате «покрыто / всего»:

| Класс | Строки | Ветви |
|---|---:|---:|
| TransactionRequestValidator | 32 / 32 | 20 / 20 |
| InMemoryRateLimiter, включая TokenBucket | 34 / 36 | 5 / 6 |
| TransactionRateLimitFilter | 17 / 20 | 8 / 8 |
| InMemoryCircuitBreaker | 48 / 50 | 12 / 12 |
| RoutingService | 10 / 10 | 6 / 6 |
| AuthorizationRequestNormalizer | 34 / 34 | 22 / 22 |
| RouteService | 73 / 73 | 12 / 14 |
| AuthorizationClient | 50 / 50 | 14 / 18 |
| AuthServiceImpl | 135 / 138 | 19 / 20 |
| Card | 39 / 39 | 16 / 16 |
| Reservation | 14 / 14 | 4 / 4 |
| CardServiceImpl | 89 / 98 | 32 / 32 |
| LuhnValidator | 18 / 19 | 13 / 14 |
| RetryServiceImpl | 10 / 11 | 3 / 4 |

Покрытие **всех** производственных классов модулей ниже: Gateway — 22,8% строк / 27,1% ветвей,
Switch — 58,6% / 77,8%, Authorization — 44,5% / 17,6%, Card Management — 22,2% / 31,1%.
Порог 70% ветвей применяется к Switch; для остальных модулей порог не установлен.
Контроллеры, persistence-адаптеры, outbox, конфигурация и остальные HTTP-клиенты не объявляются
покрытыми этим набором. Процент целевых классов не подменяет общий процент модуля.

В песочнице Windows `javac` завершался с `Cannot close compiler resources` ещё до запуска тестов;
успешная чистая сборка выполнена вне песочницы тем же JDK и Maven, без изменения производственного кода.

## Что доказывают тесты

Проверяются значения ответа, денежные суммы, содержимое передаваемых объектов, количество и порядок вызовов.
При отказах дополнительно проверяется отсутствие вызовов резервирования, изменения баланса или события успеха.
Время rate limiter задаётся `Ticker`, circuit breaker — подменённым `Clock`; `Thread.sleep` не используется.
Параллельный probe-тест синхронизирован latch и имеет ограничение ожидания.
Для RRN проверяется формат и выделенный диапазон без привязки к текущему календарному дню.

`InlineTransactions` в CMS только исполняет callback: он не имитирует commit или реальный rollback БД.
Результат SQL-upsert лимитов задан mock-репозиторием: unit доказывает передачу параметров,
выбор отказа/успеха и retry, но не правильность месячного агрегирования или конкурентного SQL.
Проверки границ `dailyLimit/monthlyLimit` относятся к доменному правилу изменения лимитов карты.

Фильтр Gateway вызывается напрямую с in-memory exchange и mock chain; это не HTTP API-прогон.
Mock Logger подтверждает попытку публикации, а mock rollback — вызов компенсации, но не доставку AMQP
и не восстановление денег в распределённой системе.
В `AuthorizationClientTest` подменена цепочка `RestClient`: проверяются решения клиента по полученному
телу ответа и исключениям, а также retry с нулевой задержкой и принудительно открытый circuit breaker.
Реальных HTTP-запросов нет; сериализация, обработчики HTTP-статусов и сетевая совместимость не проверяются.
Риски R-01…R-05 из [практики 3](../practice-3-pyramid/test-cases.md#неопределённости-и-статические-риски)
остаются предметом integration/E2E-проверок.

Есть расхождения ТЗ и реализации: например, при недоступности CMS ТЗ Authorization §5 ожидает
`05/ISSUER_TIMEOUT`, а текущий сервис возвращает `96/SERVICE_UNAVAILABLE`.
Проверки отображения исключений фиксируют текущий контракт кода; их успешность не означает
соответствия этому спорному требованию. Это продолжение вопросов [практики 2](../practice-2/test-design.md).

## CI и сдача

В [ci.yml](../../.github/workflows/ci.yml) снят `-DskipTests` из Java matrix.
Сборка `install` запускает unit-тесты, формирует покрытие и проверяет порог Switch на фазе `verify`.
Артефакт `unit-results-{service}` сохраняет доступные отчёты Surefire и JaCoCo.
Шаг загрузки запускается и при ошибке; HTML JaCoCo появится только после достижения фазы `verify`.
Тяжёлый Compose-workflow для этой практики не требуется.

Коммит и push выполняет автор. После push необходимо дождаться CI и приложить ссылку на конкретный
зелёный Actions run к Issue `practice-4`. Локальный прогон не заменяет этот шаг; до push статус CI не заявляется.
