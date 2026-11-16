# Проверки запускаются из корня репозитория: make -C app test
GRADLE := ./gradlew

.PHONY: analyze test build clean format check

analyze:
	$(GRADLE) --quiet lint

test:
	$(GRADLE) --quiet test

# Стиль проверяется до тестов: формат падает за секунды, тесты — за минуты.
check:
	$(GRADLE) --quiet ktlintCheck
	$(GRADLE) --quiet test

format:
	$(GRADLE) --quiet ktlintFormat

build:
	$(GRADLE) --quiet assembleDebug

clean:
	$(GRADLE) --quiet clean
