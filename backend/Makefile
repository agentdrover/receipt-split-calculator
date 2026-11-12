# Проверки запускаются из корня репозитория: make -C backend test
PY := uv run

.PHONY: test lint accuracy acceptance

test:
	$(PY) pytest -q

lint:
	$(PY) ruff check .

# Цели под будущие скрипты: accuracy — замер точности (#2291),
# acceptance — приёмочный прогон на реальных чеках (#2302).
accuracy:
	$(PY) python -m scripts.accuracy

acceptance:
	$(PY) python -m scripts.acceptance
