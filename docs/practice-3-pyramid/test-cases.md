# Б. Новые тест-кейсы

Автор: Константин Каренин. Дата: 15.10.2026. Все 18 кейсов — `NOT_RUN`. Unit/API-код этой практикой не реализуется.

## Требования и общий протокол

| Код | Основание |
|---|---|
| G-01 | [ТЗ Gateway §2](../../tz/02-gateway.md): обязательные поля, PAN, положительная сумма, ошибка валидации |
| G-02 | [ТЗ Gateway §4–5](../../tz/02-gateway.md): rate limit и обработка недоступного downstream; точная модель token bucket и circuit breaker — по коду |
| S-01 | [ТЗ Switch §2](../../tz/03-switch.md): BIN → эмитент; неизвестный BIN даёт отказ |
| S-02 | [ТЗ Switch §3–4](../../tz/03-switch.md): асинхронная публикация и компенсация одобренной операции при сбое |
| M-01 | [ТЗ Switch §3](../../tz/03-switch.md): DLX/DLQ, предел попыток, TTL; применимо также к карточной очереди |
| L-01 | [ТЗ Logger §2 и §6](../../tz/08-transaction-logger.md): идемпотентность сохранения, WebSocket новых записей; HTTP-конфликт — по контроллеру и обработчику ошибок |
| A-08 / A-12 | [Реестр практики 2](../practice-2/test-design.md): финансовый инвариант и уникальность RRN |
| A-05 / C-12 / C-15 | [ТЗ Authorization](../../tz/04-authorization.md), [ТЗ CMS](../../tz/05-card-management.md): месячный расход, reserve и атомарный outbox |

Точные настройки теста фиксируются в свидетельствах. Для unit не поднимаются Spring, HTTP, PostgreSQL и RabbitMQ; зависимости заменяются Mockito, время — `Ticker`/`Clock`. API проверяет HTTP и JSON одного приложения, его бизнес-зависимости могут быть double. Integration использует реальные SQL/AMQP-компоненты, явно указанные в строке. E2E запускает всю необходимую цепочку без подмены бизнес-сервисов.

Базовые данные и бюджеты ожидания заданы в [README](README.md). Перед денежным кейсом снимаются баланс, usage, резервы и outbox только своей карты. После кейса сравниваются ответ и снимки состояния. Сбой зависимости восстанавливается в teardown, фоновые симуляторы выключены. Для сообщений сопоставляются UUID id и RRN; PAN в диагностике маскируется. В unit отсутствие расхода подтверждается отсутствием вызовов записи, а в integration/E2E — реальными записями БД.

## Unit: быстрые правила и решения

| ID | Требование / техника / вид | Подготовка и шаги | Ожидаемый результат | Почему этот уровень |
|---|---|---|---|---|
| P3-01 | G-02; границы / позитивный и негативный | `InMemoryRateLimiter.forTesting`: capacity100, refill100/с, управляемый Ticker. В момент t0 вызвать `allowRequest` 101 раз для ключа K; повторить для другого IP; сдвинуть время K на 10 мс и сделать ещё два вызова | Для K: 100 true и затем false; другой ключ начинает с полного bucket; после 10 мс один true, следующий false | Проверяется алгоритм токенов, без сетевой погрешности «101 запрос за секунду» |
| P3-02 | G-02; переходы состояний / позитивный и негативный | Circuit breaker: threshold3, openDuration10с, управляемый Clock. Зарегистрировать две ошибки, затем третью; проверить до t+10с и ровно t+10с; запросить два probe одновременно, завершить разрешённый успехом; отдельный вариант — ошибкой | До третьей ошибки доступ разрешён; после неё закрыт для запросов до границы; на границе проходит ровно один probe; успех восстанавливает доступ, ошибка вновь открывает breaker | Потокобезопасная машина состояний в `InMemoryCircuitBreaker`, БД не участвует |
| P3-03 | G-01; КЭ и границы / негативный с позитивным контролем | Валидный AuthorizationRequest; отдельно заменить amount на null, -1, 0 и 1, остальные поля сохранить; вызвать `TransactionRequestValidator.validate` | null/-1/0 вызывают `TransactionValidationException`; 1 принимается. Проверить сообщение о целевом поле, а не только тип исключения | Чистая проверка суммы; преобразование исключения в HTTP отдельно в P3-07 |
| P3-04 | S-02; таблица решений / негативный | RouteService с Mockito-клиентами. Authorization → APPROVED с RRN, Logger → false; вызвать route. Повторить отдельный вариант с исходным DECLINED/51 и Logger → false | APPROVED: ровно один rollback с исходными PAN/amount/RRN и ответ DECLINED/96; исходный DECLINED: rollback не вызван, сохранён код51 | Тест оркестрации доказывает выбор ветки, финансовый результат компенсации проверяет P3-18 |
| P3-05 | C-12; классы статусов / негативный | `Card` с B12000, A3000; параметризовать INACTIVE/BLOCKED/EXPIRED/DELETED, затем контроль ACTIVE; вызвать `startReservation` | Неактивные статусы вызывают `IllegalStateException`; ACTIVE создаёт Reservation с исходными PAN/amount/RRN и RESERVED; исходный Card неизменён | Правило в доменной модели не требует JPA; скрытие DELETED в запросах проверено планом TC-C-12 |
| P3-06 | S-02, C-12; переходы / негативный | Reservation RESERVED на3000. Создать rollback на3000, вызвать `rolledBack`; затем `startRollback` на полученном резерве | Новый резерв имеет ROLLED_BACK и прежний id; повтор вызывает `RollbackAlreadySatisfiedException`; проверить сохранение суммы и PAN | Проверка повторного перехода доменной модели. Защита от конкурентного возврата — отдельная integration-задача |

Источники unit: [RateLimiter](../../services/gateway/src/main/java/com/processing/gateway/ratelimit/InMemoryRateLimiter.java), [CircuitBreaker](../../services/gateway/src/main/java/com/processing/gateway/circuitbreaker/InMemoryCircuitBreaker.java), [валидатор](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [RouteService](../../services/switch/src/main/java/com/processing/service/RouteService.java), [Card](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java), [Reservation](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java).

## API: транспортный контракт

| ID | Требование / техника / вид | Подготовка и шаги | Ожидаемый результат | Почему этот уровень |
|---|---|---|---|---|
| P3-07 | G-01; КЭ / негативный | Gateway с HTTP-stub Switch и пустым журналом stub. POST `/api/transactions` с `Content-Type: application/json`; варианты: пустое тело, оборванный JSON, валидный JSON с amount0 | HTTP400, JSON `error=VALIDATION_ERROR`; для malformed JSON сообщение про JSON, для amount0 — про сумму; Switch получил 0 запросов | Нужны чтение тела WebFlux, Jackson, фильтр и сериализация ошибки, одного unit валидатора недостаточно |
| P3-08 | G-02; отказ зависимости / негативный | Gateway, свежий circuit breaker; SWITCH_URL указывает на закрытый локальный порт. Отправить один валидный запрос покупки | HTTP503, JSON `error=SERVICE_UNAVAILABLE`, `serviceName=switch`; отсутствует ложный APPROVED | Проверяется отображение сетевой ошибки в внешний контракт; реальный Authorization не нужен |
| P3-09 | S-01; КЭ / негативный | Switch: реальные RoutingService и RouteService; Authorization и комиссия — doubles, RabbitTemplate — mock. PAN с BIN499999 при отсутствии его в тестовой таблице; POST `/api/internal/route` | HTTP200, JSON DECLINED/14/CARD_NOT_FOUND; authorize и комиссия не вызваны; попытка логирования содержит тот же STAN и отказ | Нужен HTTP-ответ контроллера, а не вся платежная цепочка; Luhn здесь не проверяется |
| P3-10 | L-01; конфликт / негативный | MVC-срез Logger с real advice и mock TransactionService. Для корректного TransactionRequest с id X mock выбрасывает `TransactionConflictException(X)`. POST `/api/internal/log` | HTTP409, `error=Transaction conflict`, `serviceName=transaction-logger`, `retryAfterMs=0`; id конфликта присутствует в диагностике | Здесь проверяется только отображение доменного конфликта в HTTP. Реальный конфликт хранения — в P3-13 |

Источники API: [ValidationFilter](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionValidationFilter.java), [DownstreamErrorFilter](../../services/gateway/src/main/java/com/processing/gateway/downstream/DownstreamErrorFilter.java), [RouteController](../../services/switch/src/main/java/com/processing/controller/RouteController.java), [InternalTransactionController](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/controller/InternalTransactionController.java), [Logger advice](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/exception/GlobalExceptionHandler.java).

## Integration: БД, транзакции и брокер

| ID | Требование / техника / вид | Реальные зависимости, подготовка и шаги | Ожидаемый результат | Почему этот уровень |
|---|---|---|---|---|
| P3-11 | C-12, C-15; атомарность / негативный | CMS и PostgreSQL Testcontainer; outbox scheduler выключен, карта B12000. После save резерва принудительно вызвать исключение при записи outbox в той же Spring-транзакции; выполнить reserve3000, новый RRN. Снять состояние после rollback | Ошибка операции; B12000, нет нового резерва и outbox этого RRN. Контроль без fault: B9000, один RESERVED и один PENDING | Проверяется реальный transaction manager и общий commit трёх записей; mock TransactionRunner этого не доказывает |
| P3-12 | A-12; граница блока и конкуренция / позитивный | PostgreSQL с rrn_seq, две независимые AuthServiceImpl с реальным LimitUsageRepository. Параллельно вызвать `generateRRN` по1001 раз на каждом экземпляре | 2002 различных RRN формата12 цифр; каждый экземпляр перешёл границу блока1000; последовательность не сброшена между вызовами | Проверяет выделение блоков нескольким экземплярам, дополняет двадцать операций TC-A-17. Для чистой проверки RRN CMS и RabbitMQ не нужны |
| P3-13 | L-01; повтор и конфликт / позитивный и негативный | Logger, PostgreSQL, RabbitMQ; реальные listener/service/mapper, WebSocketManager — spy. Отправить TransactionRequest с UUID X; после commit повторить тот же payload; затем тот же X с другой amount. Конфликт отдельно вызвать через реальный TransactionService, чтобы снять исключение без ожидания retry очереди | После одинакового повтора одна строка и один broadcast; при конфликте `TransactionConflictException`, первоначальная amount сохранена, новой рассылки нет | Проверяются БД-идемпотентность, доставка AMQP и отсутствие повторной WS-рассылки, не только HTTP409 из P3-10 |
| P3-14 | M-01; отказ consumer / негативный | RabbitMQ и Logger с полной топологией Switch, PostgreSQL доступна; очереди пусты. Опубликовать сообщение с уникальным messageId и несовместимым JSON в `smp.transactions`/`transaction.log`. Наблюдать доставки, DLQ и TTL | По требованию: после трёх неудачных обработок одно сообщение в transaction-log-dlq, нет записи transactions; после TTL60с сообщение удалено. До настройки runtime retry число попыток — спорный оракул R-03 | Требуется реальный listener и брокер. Декларация x-max-delivery отдельно не засчитывается как успешный прогон |
| P3-15 | M-01, C-15; отказ consumer / негативный | RabbitMQ, Notification и PostgreSQL, карточная топология CMS; очереди пусты. Отправить невалидный JSON с ключом `card.CardServicePatchEvent`, уникальным messageId; наблюдать DLQ | По требованию: ограниченное число доставок, переход в card-notifications-dlq, нет новой строки card_notifications; удаление по TTL60с. Предел3 подтверждается отдельно, см. R-03 | Проверяет вторую очередь и JSON-consumer, отличается от счётчика попыток publisher outbox |
| P3-16 | A-05; история / негативный | Реальный LimitUsageRepository и PostgreSQL; D6000, M10000. upsert3000 за14.10; upsert3000 за15.10; ещё upsert3000 за14.10; затем upsert2000 за15.10. После каждого вызова читать daily_amount всех дней месяца | Первые три операции разрешены: сумма daily_amount=9000; четвёртая отклонена (0 строк), сумма остаётся9000. Если SQL разрешает11000 — кандидат на дефект месячного инварианта | Проверяется месячный лимит при записи в уже существующий прошлый день; mock результата SQL скроет устаревший monthly_amount |

Источники integration: [TransactionRunner](../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunnerImpl.java), [CMS reserve](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [RRN и авторизация](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [SQL лимитов](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java), [Logger store](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java), [топология и consumers](architecture/messaging.md).

## E2E: результат для пользователя

| ID | Требование / техника / вид | Подготовка и шаги | Ожидаемый результат | Почему этот уровень |
|---|---|---|---|---|
| P3-17 | A-08, L-01; бизнес-сценарий / позитивный | Полный Compose, карта B12000/D15000/M60000, Ud=Um=0. Открыть Dashboard и подписку WS; сохранить исходные stats. POST покупки3000 через Gateway; опрашивать поиск по PAN до появления своего RRN, затем проверить UI и stats | APPROVED/00, RRN12; B9000, Ud=Um=3000 и один резерв. В Logger одна запись со своим RRN/amount/status; Dashboard показывает её; total/approved увеличились на1. Доставка в пределах тестового бюджета10с | Проверяется совместимость клиентского входа, платежной цепочки, очереди, журнала и интерфейса. Все граничные комбинации здесь не повторяются |
| P3-18 | S-02, A-08; компенсация / негативный | Отдельный полный стенд; карта с теми же параметрами. Сохранить состояние, остановить RabbitMQ до покупки3000, выполнить POST через Gateway; проверить БД. Восстановить брокер в teardown и проверить журнал своей операции | DECLINED/96; B12000, резерв ROLLED_BACK, нет двойного возврата; Ud=Um=0 по исходному финансовому инварианту. Запись APPROVED при заведомо несостоявшейся публикации отсутствует. Возврат баланса при оставшемся usage — FAIL по A-08, см. R-02 | Mock rollback подтверждает только вызов. Нужны реальные HTTP-сервисы, две транзакционные области и отказ брокера |

Новые E2E развивают пункты CP-05/06/14 чек-листа практики 2 в воспроизводимые сценарии. Эти пункты не посчитаны повторно в исходных 82 кейсах.

## Неопределённости и статические риски

| ID | Наблюдение и влияние | Правило оценки будущего прогона |
|---|---|---|
| R-01 | `LoggerClient.log` и Outbox publisher не ожидают confirm/return. ТЗ требует подтверждение публикации; нужен отдельный тест потери соединения после send | Возврат метода и статус PROCESSED не считаются доказательством доставки. Broker-down из P3-18 не покрывает неопределённый исход после отправки |
| R-02 | `AuthServiceImpl.rollback` вызывает CMS, но не уменьшает usage. При ошибке reserve authorize возвращает отказ после upsert usage | В P3-18 и старом TC-A-14 сверять реальные остатки. Код не меняет оракул «отказ не расходует лимит»; до прогона это кандидат на дефект |
| R-03 | QueueBuilder содержит x-max-delivery, явная политика трёх повторов в listener-конфигурации не найдена | P3-14/15 записывают число доставок и effective queue arguments. Нельзя объявить PASS по чтению конфигурации; при неоднозначном правиле — BLOCKED_REQUIREMENT |
| R-04 | Повтор внешней покупки и повтор AMQP-сообщения имеют разные идентификаторы и смысл | В P3-13 переотправлять именно тот же TransactionRequest.id. Повтор POST не использовать как доказательство идемпотентности consumer |
| R-05 | P3-16 разрешает работу репозитория с датами не по порядку; публичное правило времени остаётся Q-01 из практики 2 | Инвариант суммы месяца — A-05. Если backdated-записи официально запрещены, согласовать проверку запрета на API вместо молчаливого снятия риска |

Для несовпадающих HTTP-кодов, порядка проверок и границ из практики 2 сохраняются Q-01…Q-12; ожидаемые результаты не подгоняются под текущую реализацию.
