# Проверки запускаются из корня репозитория: make -C app test
GRADLE := ./gradlew
RELEASE := ../scripts/release.sh

.PHONY: analyze test build clean format check release-check release-verify release-build release-draft

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

# Релизный контур: проверки без ключа, проверка SHA и CI, подписанная сборка,
# тег и черновик релиза. Порядок и ограничения — docs/releasing.md.
release-check:
	$(RELEASE) check

release-verify:
	$(RELEASE) verify

release-build:
	$(RELEASE) build

release-draft:
	$(RELEASE) draft

clean:
	$(GRADLE) --quiet clean
