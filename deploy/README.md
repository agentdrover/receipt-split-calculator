# Выкат бэкенда

Образ: `backend/Dockerfile` (python:3.12-slim, uv, не-root, HEALTHCHECK по `/health`).
Манифесты: `deploy/k8s` — Deployment на 2 реплики с пробами и лимитами, Service,
Ingress с https.

## Порядок

1. Собрать и опубликовать образ:
   ```sh
   docker build -t ghcr.io/agentdrover/receipts-api:0.1.0 backend
   docker push ghcr.io/agentdrover/receipts-api:0.1.0
   ```
2. Создать Secret с ключом провайдера (см. `k8s/secret.example.yaml`). Ключ в
   манифесты и репозиторий не попадает.
3. Применить манифесты:
   ```sh
   kubectl apply -f deploy/k8s/deployment.yaml -f deploy/k8s/service.yaml -f deploy/k8s/ingress.yaml
   ```
4. Проверить: `curl -fsS https://<host>/health` → `{"status":"ok"}`.

## Правила хаба

Выкат на k8 идёт через проект **home-infra**, а не вручную с ноутбука: там
описаны порядок, копия состояния и откат. Этот каталог — предмет выката,
а не замена процедуре.

## Откат

`kubectl rollout undo deployment/receipts-api` возвращает предыдущий образ.
Секрет при откате не меняется.
