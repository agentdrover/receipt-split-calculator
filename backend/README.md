# receipts-api

Сервис распознавания чеков: принимает фото, возвращает позиции по схеме `receipt.v1`.
Сейчас в нём только каркас: приложение, маршрут `/health` и тестовая обвязка.

## Что внутри

```
backend/
  app/
    main.py      создание приложения
    api/         маршруты (health)
    llm/         клиент провайдера распознавания
    schemas/     схемы данных
  tests/         pytest + TestClient
  Makefile       test, lint, accuracy, acceptance
```

## Установка

Нужны Python 3.12+ и [uv](https://docs.astral.sh/uv/).

```sh
cd backend
uv sync
```

## Запуск

```sh
cd backend
uv run uvicorn app.main:app --reload
curl -s localhost:8000/health   # {"status":"ok"}
```

## Проверки

Из корня репозитория:

```sh
make -C backend test
make -C backend lint
```

`make -C backend accuracy` и `make -C backend acceptance` появятся вместе со скриптами
в задачах #2291 и #2302.

## Распознавание чека

`POST /api/v1/receipts/recognize` — multipart, поле `file`, заголовок `X-Installation-Id`.
Возвращает `receipt.v1` (см. `app/schemas/receipt.py`).

Переменные окружения:

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `LLM_API_KEY` | — | ключ провайдера; без него эндпоинт отвечает 503 |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | OpenAI-совместимый адрес |
| `LLM_MODEL` | `gpt-4o-mini` | модель распознавания |

Коды ответа: `200` — чек; `413` — файл больше 8 МБ; `422` — пусто или чек не читается;
`429` — больше 20 запросов в час с одной установки; `502` — провайдер не дал валидный
ответ после повтора; `503` — не задан ключ.
