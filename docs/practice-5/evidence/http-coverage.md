# HTTP evidence: local run 1

Counts include fixture preparation, cleanup and Awaitility polling; they are HTTP calls, not independent test cases.
Source: run-1/requests.log, produced by Api.record.

| Service | Method | Path | Status codes | Calls |
|---|---|---|---|---|
| Authorization (faults) | POST | /api/internal/authorize | 503 | 1 |
| Authorization (faults) | POST | /api/internal/rollback | 503 | 1 |
| Authorization | GET | /health | 200 | 1 |
| Authorization | POST | /api/internal/authorize | 200, 400, 403, 404, 422 | 47 |
| Authorization | POST | /api/internal/rollback | 200, 404, 409 | 3 |
| BIN | GET | /api/bin/{bin} | 200, 404, 500 | 4 |
| CMS | DELETE | /api/bins/{bin} | 204 | 5 |
| CMS | DELETE | /api/cards/{pan} | 204, 404 | 81 |
| CMS | DELETE | /api/cards/123456789012345 | 400 | 1 |
| CMS | GET | /api/cards/{pan} | 200, 404 | 45 |
| CMS | GET | /api/cards/{pan}7 | 400 | 1 |
| CMS | GET | /api/cards/123456789012345 | 400 | 1 |
| CMS | GET | /api/cards/ABCDEFGHIJKLMNOP | 400 | 1 |
| CMS | GET | /health | 200 | 1 |
| CMS | PATCH | /api/cards/{pan} | 200, 400, 404 | 10 |
| CMS | PATCH | /api/cards/123456789012345 | 400 | 1 |
| CMS | POST | /api/bins | 201 | 5 |
| CMS | POST | /api/cards/{pan}/reserve | 200 | 1 |
| Gateway (faults) | DELETE | /api/cards/{pan} | 503 | 1 |
| Gateway (faults) | GET | /api/cards | 503 | 1 |
| Gateway (faults) | GET | /api/cards/{pan} | 503 | 1 |
| Gateway (faults) | GET | /api/dashboard/charts | 503 | 1 |
| Gateway (faults) | GET | /api/dashboard/recent | 503 | 1 |
| Gateway (faults) | GET | /api/dashboard/stats | 503 | 1 |
| Gateway (faults) | GET | /api/transactions/export | 503 | 1 |
| Gateway (faults) | GET | /api/transactions/search | 503 | 1 |
| Gateway (faults) | PATCH | /api/cards/{pan} | 503 | 1 |
| Gateway (faults) | POST | /api/cards | 503 | 1 |
| Gateway (faults) | POST | /api/cards/{pan}/reserve | 503 | 1 |
| Gateway (faults) | POST | /api/cards/{pan}/rollback | 503 | 1 |
| Gateway (faults) | POST | /api/cards/bulk-update | 503 | 1 |
| Gateway (faults) | POST | /api/cards/generate | 503 | 1 |
| Gateway (faults) | POST | /api/simulator/merchant/fee | 503 | 1 |
| Gateway (faults) | POST | /api/simulator/merchant/run | 503 | 1 |
| Gateway (faults) | POST | /api/simulator/terminal/run | 503 | 1 |
| Gateway (faults) | POST | /api/simulator/terminal/start-continuous | 503 | 1 |
| Gateway (faults) | POST | /api/simulator/terminal/stop | 503 | 1 |
| Gateway (faults) | POST | /api/transactions | 400, 429, 503 | 10 |
| Gateway | DELETE | /api/cards/{pan} | 204 | 1 |
| Gateway | GET | /api/cards | 200, 400, 413 | 12 |
| Gateway | GET | /api/cards/{pan} | 200 | 5 |
| Gateway | GET | /api/dashboard/charts | 200, 400 | 3 |
| Gateway | GET | /api/dashboard/recent | 200, 400 | 6 |
| Gateway | GET | /api/dashboard/stats | 200 | 1 |
| Gateway | GET | /api/simulator/merchants | 404 | 1 |
| Gateway | GET | /api/transactions/export | 200, 400 | 2 |
| Gateway | GET | /api/transactions/search | 200, 400 | 136 |
| Gateway | GET | /cards-docs | 200 | 1 |
| Gateway | GET | /health | 200 | 1 |
| Gateway | GET | /logger-docs | 200 | 1 |
| Gateway | GET | /merchant-docs | 200 | 1 |
| Gateway | GET | /switch-docs | 200 | 1 |
| Gateway | GET | /terminal-docs | 200 | 1 |
| Gateway | PATCH | /api/cards/{pan} | 200 | 14 |
| Gateway | POST | /api/cards | 201, 400, 404 | 91 |
| Gateway | POST | /api/cards/{pan}/reserve | 200, 400, 402, 404, 409 | 15 |
| Gateway | POST | /api/cards/{pan}/rollback | 200, 400, 404, 409 | 9 |
| Gateway | POST | /api/cards/bulk-update | 200, 400 | 2 |
| Gateway | POST | /api/cards/generate | 201, 400, 404 | 6 |
| Gateway | POST | /api/simulator/merchant/fee | 200, 400, 404 | 3 |
| Gateway | POST | /api/simulator/merchant/run | 200, 400 | 10 |
| Gateway | POST | /api/simulator/terminal/run | 200, 400 | 10 |
| Gateway | POST | /api/simulator/terminal/start-continuous | 200 | 1 |
| Gateway | POST | /api/simulator/terminal/stop | 200 | 1 |
| Gateway | POST | /api/transactions | 200, 400 | 50 |
| Logger | GET | /health | 200 | 1 |
| Logger | POST | /api/internal/log | 200, 201, 400, 409 | 147 |
| Merchant | GET | /api/simulator/merchants | 200 | 1 |
| Merchant | GET | /health | 200 | 1 |
| Switch (faults) | POST | /api/internal/route | 200 | 1 |
| Switch | GET | /health | 200 | 1 |
| Switch | POST | /api/internal/route | 200 | 2 |
| Terminal | GET | /health | 200 | 1 |
