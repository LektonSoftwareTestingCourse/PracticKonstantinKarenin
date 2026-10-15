# А2. Контейнеры — C4 Level 2

Порты ниже опубликованы на хосте. Внутри Compose Java-приложения слушают 8080; HTTP-клиенты используют имена сервисов. Сплошная стрелка — синхронный вызов или JDBC, пунктир — AMQP-доставка.

```mermaid
flowchart TB
    user["Браузер / HTTP-клиент"]
    subgraph deployment["Учебное окружение Docker Compose"]
        web["Dashboard :3000<br/>React + nginx"]
        terminal["Terminal Simulator :8085<br/>Java / Spring Boot"]
        merchant["Merchant Acquirer :8084<br/>Java / Spring Boot"]
        gw["Gateway :8080<br/>Spring Cloud Gateway"]
        sw["Switch :8082<br/>Java / Spring Boot"]
        auth["Authorization :8083<br/>Java / Spring Boot"]
        cms["Card Management :8081<br/>Java / Spring Boot"]
        lookup["Bin Lookup :8096<br/>имитатор внешнего API"]
        logger["Transaction Logger :8088<br/>Java / Spring Boot"]
        notification["Notification Service :8097<br/>Java / Spring Boot"]
        db[("PostgreSQL :5432<br/>свои таблицы у каждого владельца")]
        mq[("RabbitMQ :5672<br/>Management :15672")]
    end
    user --> web
    user --> gw
    web -->|"REST через nginx /api"| gw
    web -->|"WebSocket через nginx /ws"| logger
    terminal -->|"Покупки"| gw
    terminal -->|"Пул карт"| cms
    merchant -->|"Покупки и данные карт"| gw
    gw -->|"POST transactions → internal/route"| sw
    gw -->|"cards"| cms
    gw -->|"transactions/search, dashboard"| logger
    gw -->|"simulator/terminal"| terminal
    gw -->|"simulator/merchant"| merchant
    sw -->|"authorize / rollback"| auth
    sw -->|"Комиссия эквайера"| merchant
    auth -->|"GET карты, reserve, rollback"| cms
    auth -->|"GET api/bin/BIN"| lookup
    sw -.->|"transaction.log"| mq
    mq -.->|"transaction-log"| logger
    cms -.->|"outbox: card.*"| mq
    mq -.->|"card-notifications"| notification
    cms --> db
    auth --> db
    merchant --> db
    logger --> db
    notification --> db
```

Базовый стенд содержит 10 приложений и два инфраструктурных контейнера. Autoscaler, Prometheus, Grafana, Loki и Promtail запускаются профилем `observability` и в бизнес-цепочку этой схемы не входят. Формулировка «11 сервисов» в старом чек-листе не совпадает с базовым Compose.

Пять сервисов обращаются к PostgreSQL: CMS, Authorization, Merchant Acquirer, Logger, Notification. Один инстанс БД не означает разрешение читать чужие таблицы в коде приложения. Таблицы, например `cards`, `limit_usage`, `transactions`, принадлежат разным сервисам; тестовые SQL-снимки служат наблюдению.

Источники: [Compose](../../../docker-compose.yaml), [маршруты Gateway](../../../services/gateway/src/main/resources/application.yml), [nginx Dashboard](../../../services/dashboard/nginx.conf), [RoutingService](../../../services/switch/src/main/java/com/processing/service/RoutingService.java), [клиент комиссии](../../../services/switch/src/main/java/com/processing/service/AcquiringFeeClient.java), [CardProvider Merchant](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/service/CardProvider.java).

Границы для тестирования: HTTP-контракты Gateway/Switch — API; SQL, AMQP и транзакции отдельного сервиса — integration; покупка через Gateway с наблюдением результата в Dashboard — E2E. Тест связи двух сервисов не становится E2E только из-за наличия HTTP.
