# API-тесты СМП — отчёт

Практика 5, Константин Каренин. Модуль: `services/api-tests`.

Стек: Java 21, Maven, JUnit Jupiter 5.11.4, REST-assured 5.4.0, Hamcrest 2.2,
Awaitility 4.2.2, Jackson и JSON Schema Validator. Версии закреплены в POM.
Подготовка состояния, действия и наблюдения выполняются через HTTP. JDBC, AMQP-клиентов
и Testcontainers в тестовом модуле нет. PostgreSQL и RabbitMQ — зависимости реального Compose-стека.

Основание: [условие практики](https://github.com/LektonSoftwareTestingCourse/Lectures/blob/main/materials/module-05-api-tests/practice/practice-01.md),
[`api-spec.md`](../api-spec.md), [`openapi.yaml`](../api/openapi.yaml),
[`tz/04`](../../tz/04-authorization.md), [`tz/05`](../../tz/05-card-management.md),
[`tz/08`](../../tz/08-transaction-logger.md), контроллеры и
[`маршруты Gateway`](../../services/gateway/src/main/resources/application.yml).
[Issue с примером](https://github.com/LektonSoftwareTestingCourse/Practic/issues/156)
использовано как ориентир по составу артефактов; код набора написан для этого репозитория.

## 1. Покрытие

Локально 12.11.2026 выполнены **три последовательных прогона по 349 запусков**:
74 метода в 14 классах, 0 падений, 0 ошибок, 0 пропусков в каждом прогоне.
Каждый `clean verify` также завершил Checkstyle без нарушений.

Доказательства: [прогон 1](evidence/run-1/summary.txt), [прогон 2](evidence/run-2/summary.txt),
[прогон 3](evidence/run-3/summary.txt), [фактический HTTP-реестр](evidence/http-coverage.md).
В HTTP-реестре указаны количество запросов и наблюдавшиеся статусы, включая подготовку,
уборку и опрос Awaitility; это не выдаётся за число независимых тест-кейсов.

Реестр составлен по спецификации, контроллерам и маршрутам Gateway.
Коды ниже обозначают проверенные ветки; технические отказы БД вынесены в ограничения раздела 5.

| Эндпоинт | Проверяемые статусы | Основные классы |
|---|---|---|
| `GET /health` у Gateway и сервисов | 200 | MetadataTest |
| `POST /api/cards` | 201, 400, 404, 503 | CardTest, UnavailableServiceTest |
| `GET /api/cards` | 200, 400, 413, 503 | CardCollectionTest, UnavailableServiceTest |
| `GET /api/cards/{pan}` | 200, 400, 404, 503 | CardTest, UnavailableServiceTest |
| `PATCH /api/cards/{pan}` | 200, 400, 404, 503 | CardTest, UnavailableServiceTest |
| `DELETE /api/cards/{pan}` | 204, 400, 404, 503 | CardTest, UnavailableServiceTest и cleanup |
| `POST /api/cards/generate` | 201, 400, 404, 503 | CardCollectionTest, UnavailableServiceTest |
| `POST /api/cards/bulk-update` | 200, 400, 503 | CardTest, UnavailableServiceTest |
| `POST /api/cards/{pan}/reserve` | 200, 400, 402, 404, 409, 503 | FundsTest, UnavailableServiceTest |
| `POST /api/cards/{pan}/rollback` | 200, 400, 404, 409, 503 | FundsTest, UnavailableServiceTest |
| `POST /api/transactions` | 200, 400, 429, 503 | AuthorizationTest, GatewayValidationTest, UnavailableServiceTest |
| `GET /api/transactions/search` | 200, 400, 503 | SearchTest, UnavailableServiceTest |
| `GET /api/transactions/export` | 200, 400, 503 | SearchTest, UnavailableServiceTest |
| `GET /api/dashboard/stats` | 200, 503 | DashboardTest, UnavailableServiceTest |
| `GET /api/dashboard/recent` | 200, 400, 503 | DashboardTest, UnavailableServiceTest |
| `GET /api/dashboard/charts` | 200, 400, 503 | DashboardTest, UnavailableServiceTest |
| `POST /api/simulator/terminal/run` | 200, 400, 503 | SimulatorTest, UnavailableServiceTest |
| `POST /api/simulator/terminal/start-continuous`, `/stop` | 200, 503 | SimulatorTest, UnavailableServiceTest |
| `POST /api/simulator/merchant/run` | 200, 400, 503 | SimulatorTest, UnavailableServiceTest |
| `POST /api/simulator/merchant/fee` | 200, 400, 404, 503 | SimulatorTest, UnavailableServiceTest |
| `POST /api/internal/route` (Switch) | 200 | RoutingTest; отсутствие валидации — D-13 |
| `POST /api/internal/authorize` | 200, 400, 403, 404, 422, 503 | AuthorizationTest, InternalValidationTest, UnavailableServiceTest |
| `POST /api/internal/rollback` (Authorization) | 200, 404, 409, 503 | AuthorizationTest, UnavailableServiceTest |
| `POST /api/internal/log` | 201, 200, 400, 409 | LoggerDeliveryTest, LoggerValidationTest |
| `GET /api/bin/{bin}` | 200, 404, 500 | MetadataTest |
| `GET /{switch,logger,terminal,merchant,cards}-docs` | 200 | MetadataTest |

`GET /api/simulator/merchants` проверяется напрямую (200) и через Gateway (404):
контроллер есть, но маршрут не совпадает с его путём. `POST/DELETE /api/bins` используются
для API-подготовки изолированных наборов карт. Управление BIN и уведомлениями не открыто
в Gateway; уведомления не участвуют в синхронной цепочке авторизации/логирования.

Дополнительные endpoints из кода, отсутствующие в краткой спецификации: коллекция карт,
PATCH/DELETE, bulk-update, reserve/rollback, export, charts, continuous, fee и BIN Lookup.
WebSocket, Actuator/Prometheus и Swagger UI как HTML-приложение не являются целевыми бизнес-HTTP-контрактами.

Количество запусков по классам (параметризованные строки считаются отдельно):

| Класс | Запусков | Падений / ошибок / пропусков |
|---|---|---|
| AuthorizationTest | 19 | 0 / 0 / 0 |
| CardCollectionTest | 16 | 0 / 0 / 0 |
| CardTest | 32 | 0 / 0 / 0 |
| DashboardTest | 10 | 0 / 0 / 0 |
| FundsTest | 20 | 0 / 0 / 0 |
| GatewayValidationTest | 45 | 0 / 0 / 0 |
| InternalValidationTest | 29 | 0 / 0 / 0 |
| LoggerDeliveryTest | 22 | 0 / 0 / 0 |
| LoggerValidationTest | 51 | 0 / 0 / 0 |
| MetadataTest | 16 | 0 / 0 / 0 |
| RoutingTest | 3 | 0 / 0 / 0 |
| SearchTest | 40 | 0 / 0 / 0 |
| SimulatorTest | 23 | 0 / 0 / 0 |
| UnavailableServiceTest | 23 | 0 / 0 / 0 |

## 2. Бизнес-отказы

| Исход | Код | Причина | Путь и подготовка через API |
|---|---|---|---|
| Карта не найдена | 14 | CARD_NOT_FOUND | Случайный PAN с известным BIN и с неизвестным BIN, запрос через Gateway |
| Блокировка | 05 | CARD_BLOCKED | Создать карту, PATCH status=BLOCKED |
| Неактивная карта | 05 | CARD_INACTIVE | Создать карту, PATCH status=INACTIVE |
| Истекший срок | 54 | CARD_EXPIRED | PATCH status=EXPIRED; отдельно ACTIVE-карта с датой запроса после срока |
| Недостаточно средств | 51 | INSUFFICIENT_FUNDS | Баланс 10000, запрос 10001 |
| Дневной лимит | 61 | EXCEEDS_AMOUNT_LIMIT | dailyLimit=100, запрос 101, баланс достаточен |
| Месячный лимит | 61 | EXCEEDS_AMOUNT_LIMIT | monthlyLimit=100, запрос 101, дневной лимит не препятствует |
| CMS недоступен | 96 | SERVICE_UNAVAILABLE | Отдельный Authorization с CMS URL на закрытый TCP-порт |
| Публикация не удалась | 96 | System error | Отдельный Switch, HTTP-авторизация успешна, публикация недоступна, проверяется компенсация |
| Откатываемая операция отсутствует | 14 | TRANSACTION_NOT_FOUND | Случайный RRN при существующей карте |
| Повторный откат | 05 | ALREADY_ROLLED_BACK | Авторизация, откат, повтор того же отката |

Совпадающие коды 05, 61 и 96 проверяются вместе с причиной. Для срока проверены разные пути:
статус EXPIRED и дата запроса при ACTIVE-карте. Последний день месяца срока допустим,
следующий день — нет. После каждого отказа проверяется отсутствие списания.

## 3. Валидация и тест-дизайн

| Контракт / поле | Выделенные классы и границы |
|---|---|
| Gateway: обязательные строки | null, пустая строка, пробелы, корректное значение |
| Gateway: mti | 0100; неподдерживаемое 0200 |
| Gateway: pan | 15 / 16 / 17 символов; 16 букв |
| Gateway: amount | null, -1, 0, 1; равенство балансу; баланс + 1 |
| Gateway: currencyCode | длина 2 / 3 / 4 |
| Gateway: mcc | длина 3 / 4 / 5; 4 буквы |
| HTTP JSON | пустое тело, null, сломанный JSON, массив, неверный тип числа |
| AuthorizationRequest напрямую | каждая обязательность; ExactSize: N−1 / N / N+1; отрицательная сумма |
| Создание карты | обязательность bin/name/currency; BIN 5/6/7, буквы; регистр имени; код валюты; лимит −1 |
| Reserve / rollback | сумма −1/0/1; RRN короткий/длинный/буквенный; неизвестная карта/операция; повтор |
| Logger: обязательные поля | каждое из 14 полей по отдельности null; обязательные строки пустые |
| Logger: форматы | STAN и processingCode 5/6/7, буквы; PAN 15/16/17, буквы; currency и MCC |
| Logger: максимальная длина | mti=5, rrn=13, terminalId=9, terminalType=11, merchantId=16, acquirerId=11, issuerId=11, authCode=7, declineReason=101 |
| Logger: числа | amount −1/0/1, acquiringFee −1/0, processingTimeMs −1/0 |
| Генерация карт | count −1/0/1/2/10001, пустой/неизвестный BIN |
| Поиск транзакций | каждый из 8 фильтров отдельно; пустой результат; invalid enum/date; перевёрнутый диапазон |
| Пагинация транзакций | limit −1/0/1/500/501; offset −1/0/1/2/3; total сохраняется, проверяется ID начала страницы |
| Пагинация карт | limit −1/0/1/10000/10001; offset −1/0/1/2; собственный BIN |
| Даты поиска | UTC 00:00:00 и 23:59:59.999 включены; соседние сутки исключены |
| Dashboard | recent limit −1/0/1/20/500/501; charts hour/day/неизвестное значение; пустой временной интервал |
| Симуляторы | все 5 терминальных и 4 мерчантских сценария; count/tps −1/0/1; неизвестный сценарий |

Попарное покрытие поиска: 10 бинарных факторов (параметр передан / используется значение
по умолчанию), 16 строк вместо 1024. Значение столбца k вычисляется как чётность `row & k`,
k=1..10, row=0..15. Любые два различных ненулевых столбца независимы над GF(2), поэтому
все четыре комбинации каждой пары присутствуют по четыре раза. При любом наборе фильтров
проверяется присутствие созданной этим тестом записи, а не произвольная запись из общего журнала.
Невалидные параметры проверяются отдельно, по одному нарушению в запросе.
Накопительные лимиты проверяются двумя платежами: исчерпание за одни сутки,
сохранение месячного исчерпания на следующий день и сброс обоих лимитов в следующем месяце.

Идемпотентность Logger: новая запись (201), точный повтор (200, ровно одна строка),
один ID с изменением каждого бизнес-поля (409, исходная запись сохранена), другой ID (201),
эквивалентный масштаб BigDecimal и изменение createdAt (200 по правилам TransactionMapper.matches).

## 4. Расхождения контракта и реализации

Комментарии D-xx в тестах обозначают осознанные проверки наблюдаемого поведения.
Они не исправляют реализацию и не скрывают противоречие: при изменении поведения тест
должен упасть, после чего ожидание и эта таблица пересматриваются вместе.

| ID | Декларировано | Проверяемое поведение реализации | Источник / классификация |
|---|---|---|---|
| D-01 | mti ответа 0110 | Успешный Authorization возвращает mti запроса 0100 | api-spec, AuthorizationResponse.approved; дефект контракта, средняя |
| D-02 | Повтор log возвращает краткий LogResponse | Возвращается полная TransactionResponse | openapi, InternalTransactionController; документация, низкая |
| D-03 | Свежий баланс после авторизации | Gateway кеширует GET карты; списание выполняется внутренним HTTP-вызовом и не инвалидирует этот кеш | ResponseCachingGatewayFilterFactory; риск устаревших данных, средняя; проверки баланса идут напрямую в CMS |
| D-05 | Ошибка валидации объясняет поле и правило | CMS часто сообщает только правило, Authorization использует стандартную Spring-ошибку без поля | обработчики ошибок; дефект диагностики, средняя |
| D-06 | Ошибка диапазона объясняет причину | dateFrom > dateTo даёт 400 с пустым message | DateRangeValidator и GlobalExceptionHandler; дефект диагностики, средняя |
| D-07 | Возврат не больше зарезервированной суммы | Резерв 100, возврат 101: HTTP 200, баланс 10000 → 9900 → 10001 | Reservation.startRollback, Card.withRollback; подтверждённый дефект, высокая значимость |
| D-08 | Список мерчантов доступен в системе | Прямой `/api/simulator/merchants` есть, Gateway его не маршрутизирует | контроллер против predicate merchant/**; документация/маршрутизация, низкая |
| D-09 | expiryDate MMYY | YearMonth возвращается в формате yyyy-MM | api-spec против CardModel; дефект формата, средняя |
| D-10 | Authorization: только успешный HTTP 200 описан в кратком контракте | Бизнес-исходы дают также 400, 403, 404, 422, 503 | AuthControllerImpl; неполная документация |
| D-11 | Симулятор терминалов запускается по примеру count+scenario | DTO требует также tps > 0 | api-spec против TerminalRunRequest; документация |
| D-12 | Единая ErrorResponse | 429 содержит retryAfterMs числом и не содержит timestamp/serviceName | TransactionRateLimitFilter; неоднородный контракт ошибок |
| D-13 | STAN — ровно 6 цифр; входные DTO валидируются | Прямой Switch принимает STAN=12345 с неизвестным BIN и отвечает 200/14 с тем же коротким STAN | RouteController без @Valid; подтверждённый дефект валидации |

Отдельно уточнён публичный контракт удаления: DELETED существует только в домене,
HTTP enum его не допускает; после DELETE карта скрыта, последующие GET/DELETE дают 404.
Это корректное поведение, а не дефект.

В таблицу заносятся только расхождения этого набора; длинный список дефектов из чужого
issue не принимается за результат локальной проверки.

## 5. Обоснование достаточности и ограничения

Проверки разделены по доменам, а значения — по независимым правилам DTO и веткам
контроллеров. Известное состояние задаётся явно: баланс 10000, dailyLimit 10000,
monthlyLimit 100000, ACTIVE. Для отказа изменяется один фактор. Идентификаторы транзакций —
UUID; PAN генерирует API; STAN/RRN и собственные BIN/issuer создаются заново.
Уборка меняет только карты и BIN своего теста. Журнал не очищается; запросы привязаны к своим данным.

`Api` централизует RequestSpecification, адреса, заголовки, таймауты и журнал запросов.
`Data` собирает тела, `ApiTest` содержит подготовку и уборку. REST-assured/Hamcrest проверяют
не только статус, но значения, null, формат генерируемых полей, размеры и заголовки.
JSON Schema проверяется в сквозном тесте авторизации. GPath отбирает собственную запись по RRN/ID.

Асинхронность: максимум 15 секунд, опрос 250 мс для появления записи; отсутствие наблюдается
непрерывно 2 секунды с опросом 200 мс и общим пределом 4 секунды. Контрольная успешная
транзакция исключает ложный успех из-за полностью сломанного логирования. Фиксированных пауз нет.

Отказы воспроизводятся дополнительными экземплярами сервисов из
`docker-compose.api-tests.yaml`: отказ соединения HTTP, малый rate limit, недоступная публикация
в отдельном Switch. Основные контейнеры не останавливаются. Состояние брокера/БД напрямую не читается.

Осознанно вне покрытия:

- Отказы PostgreSQL, SQL-ограничения, конкурирующие транзакции и доставка/DLQ RabbitMQ — модуль 6.
- DB_UNAVAILABLE, UNKNOWN_REASON, RESERVATION_FAILED, ROLLBACK_FAILED и внутренние исключения, которые невозможно детерминированно
  получить валидными HTTP-запросами, остаются unit-проверками практики 4 и задачами модуля 6.
- Коллизия сгенерированного PAN (заявленный 409 create) неуправляема через публичный API.
- Полный объём 10000 генерируемых карт и 50000 строк CSV не создаётся: проверяются небольшие
  выборки и внешняя граница 10001; большие объёмы — нагрузочная работа.
- Распределение случайных исходов симуляторов не фиксируется; проверяются инварианты счётчиков.
- Отказы во время graceful shutdown и смена Docker IP не воспроизводятся в этом наборе:
  для них требуются отдельные управляемые сценарии развёртывания.

Эти исключения не означают, что абсолютно все теоретические 5xx покрыты. Таблица раздела 1
показывает фактическую границу набора, а не обещание покрытия произвольного инфраструктурного сбоя.

## 6. Доказательства работоспособности проверок

`node scripts/check-api-mutations.mjs` последовательно изменяет реализацию, пересобирает
только затронутый сервис и запускает выбранный тест. В `finally` исходник и образ восстанавливаются.
Ошибка компиляции/запуска или отсутствие выполненных тестов не считаются обнаруженной мутацией:
скрипт требует AssertionError в JUnit XML и отсутствие ошибок исполнения.

| Тест | Намеренная мутация | Результат |
|---|---|---|
| LoggerDeliveryTest.repeatedDeliveryReturnsExistingTransactionWithoutCreatingAnotherRow | Оба поиска существующего ID в TransactionService.store заменяются на Optional.empty; дедупликация отключена | [КРАСНЫЙ: 1 AssertionError, 0 ошибок](evidence/mutations/deduplication.xml) |
| GatewayValidationTest.rejectsOneInvalidField | `amount <= 0` заменяется на `< 0` | [КРАСНЫЙ: 1 AssertionError, 0 ошибок](evidence/mutations/zero-amount.xml) |
| AuthorizationTest.directApprovalHasFormattedGeneratedFields | Возвращаемый RRN заменяется строкой BROKEN | [КРАСНЫЙ: 1 AssertionError, 0 ошибок](evidence/mutations/rrn-format.xml) |

Итог: [3 мутации из 3 обнаружены](evidence/mutations/summary.txt).
После восстановления реализация прошла три полных `clean verify` (раздел 1).
Изменений production-исходников по итогам мутаций в git diff нет.

## 7. Порядок запуска

Нужны Java 21, Maven, Node.js 22+ и работающий Docker Compose v2.
Node используется только как переносимый запускатель; сами тесты написаны на Java/JUnit.

Из корня репозитория:

```text
node scripts/run-api-tests.mjs
```

Скрипт собирает JAR Maven на хосте, упаковывает их в Docker-образы, поднимает Compose-стек,
дожидается healthchecks и выполняет `mvn -B -f services/pom.xml -Papi-tests clean verify`.
`.env.example` передаётся напрямую, ручное создание `.env` не требуется.
Dashboard и observability не поднимаются: HTTP-клиентам тестового набора они не нужны.

```text
node scripts/run-api-tests.mjs --repeat=3
node scripts/run-api-tests.mjs --existing
node scripts/check-api-mutations.mjs
```

На Windows скрипт ищет Maven в PATH, затем во встроенных инструментах установленной IntelliJ IDEA.
При необходимости `MAVEN_CMD` задаёт полный путь до `mvn.cmd`/`mvn`; на этом компьютере
автоматическое обнаружение встроенного Maven 3.9.11 проверено без этой переменной.
`--existing` использует уже поднятый тестовый стек, включая fault-экземпляры.
`--down` после прогона останавливает его контейнеры, сохраняя тома.

Точечный запуск:

```text
mvn -f services/api-tests/pom.xml test -Dtest=LoggerDeliveryTest
```

Адрес каждого сервиса переопределяется `-Dapi.gateway.url=...`, `-Dapi.cards.url=...`,
`-Dapi.authorization.url=...`, `-Dapi.logger.url=...`; аналогично switch, merchant, terminal,
bin, gateway-faults, authorization-faults и switch-faults.

CI: [api-tests.yml](../../.github/workflows/api-tests.yml) — автоматический запуск при push
в practice-5 / practice-5-* / main и при PR с изменениями сервисов; также workflow_dispatch.
Пайплайн выполняет три прогона, публикует surefire XML, HTTP-журнал и логи Compose.
Дополнительные экземпляры занимают порты 28080, 28082 и 28083.

Наличие успешного удалённого CI здесь не утверждается: push и запуск Actions не выполняются по просьбе автора.
