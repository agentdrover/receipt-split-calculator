# Проверки запускаются из корня репозитория: make -C app test
GRADLE := ./gradlew

.PHONY: analyze test build clean

analyze:
	$(GRADLE) --quiet lint

test:
	$(GRADLE) --quiet test

build:
	$(GRADLE) --quiet assembleDebug

clean:
	$(GRADLE) --quiet clean
