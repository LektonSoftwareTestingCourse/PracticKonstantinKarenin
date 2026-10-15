# А4. Последовательности покупки и компенсации

Схемы описывают текущие вызовы. Требования к неизменности денег и лимитов проверяются отдельно: наличие стрелки rollback не означает успешное восстановление состояния.

## Покупка и бизнес-отказ

```mermaid
sequenceDiagram
    actor Client as HTTP-клиент
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant BIN as Bin Lookup
    participant DB as PostgreSQL
    participant MERCH as Merchant Acquirer
    participant MQ as RabbitMQ
    participant LOG as Logger
    Client->>GW: POST /api/transactions
    GW->>GW: rate limit, validation, circuit breaker
    GW->>SW: POST /api/internal/route
    SW->>SW: нормализация, BIN → issuerId
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: GET /api/cards/PAN
    CMS-->>AUTH: карта
    AUTH->>BIN: GET /api/bin/BIN
    BIN-->>AUTH: issuerId или ошибка / empty
    AUTH->>AUTH: статус, срок, баланс
    alt Ранний бизнес-отказ
        AUTH-->>SW: DECLINED / бизнес-код
    else Ранние проверки пройдены
        AUTH->>DB: upsertLimitUsage
        alt Лимит превышен
            DB-->>AUTH: 0 изменённых строк
            AUTH-->>SW: DECLINED / 61
        else Лимит разрешает
            DB-->>AUTH: 1 изменённая строка
            AUTH->>DB: блок RRN из rrn_seq при необходимости
            AUTH->>CMS: POST /api/cards/PAN/reserve
            CMS->>DB: lock, reserve, баланс, outbox
            CMS-->>AUTH: результат reserve
            AUTH-->>SW: APPROVED / 00 или отказ reserve
        end
    end
    SW->>MERCH: HTTP-запрос комиссии
    MERCH-->>SW: комиссия или fallback клиента
    SW->>MQ: convertAndSend(transaction.log)
    MQ-->>SW: возврат вызова публикации
    SW-->>GW: ответ авторизации
    GW-->>Client: HTTP + JSON
    MQ-->>LOG: доставка transaction-log
    LOG->>DB: сохранить TransactionRequest.id
    LOG-->>Client: WebSocket через Dashboard nginx при подписке
```

Доставка в Logger может произойти до или после клиентского ответа; между ними нет гарантированного порядка. Сбой Bin Lookup допускает продолжение, но в `AuthServiceImpl` полученный issuerId сейчас только логируется. Ранний выход при ошибке GET CMS происходит до обращения к Bin Lookup и не показан отдельной веткой для компактности. Неизвестный BIN Switch отклоняет ещё до Authorization, затем также пытается записать отказ в очередь.

## Сбой публикации после одобрения

```mermaid
sequenceDiagram
    participant SW as Switch RouteService
    participant MQ as RabbitMQ
    participant AUTH as Authorization
    participant CMS as Card Management
    participant DB as PostgreSQL
    actor Client as Клиент через Gateway
    Note over SW,AUTH: Authorization уже вернул APPROVED<br/>reserve и usage существуют
    SW->>MQ: публикация TransactionRequest
    MQ--xSW: AmqpException
    SW->>AUTH: POST /api/internal/rollback (PAN, amount, RRN)
    AUTH->>CMS: rollback по PAN / RRN
    CMS->>DB: резерв → ROLLED_BACK, возврат баланса, outbox
    CMS-->>AUTH: результат
    AUTH-->>SW: RollbackResponse
    SW->>SW: логирование результата компенсации
    SW-->>Client: DECLINED / 96 / System error
    Note over AUTH,DB: В rollback Authorization нет изменения limit_usage
```

Если LoggerClient вернул false для исходного DECLINED, Switch не вызывает rollback и возвращает исходный отказ. В ветке APPROVED клиентский код 96 не доказывает успешную компенсацию: Switch логирует её результат, а тело ответа остаётся системной ошибкой. Сохранение usage при отказе reserve и невосстановление usage при rollback — риски для тестов, а не подтверждённые результаты прогона.

Источники: [RouteService](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [LoggerClient](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [AuthServiceImpl](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [LimitUsageRepository](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java), [CardServiceImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [TransactionService](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java).
