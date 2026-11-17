# Оформление репозитория — спецификация

Версия 1.1 · 17 ноября 2026
Проект: `receipt-split-calculator`
Репозиторий: `agentdrover/receipt-split-calculator` — публичный, default-ветка `main`

---

## 1. Коротко

Репозиторий публичный, но юридически закрыт: лицензии нет. Стиль Kotlin никем
не проверяется и разошёлся. Подписанный APK не собирается. Эта спека фиксирует,
что правим, в каком порядке, чем проверяем и что сознательно оставляем за
границей.

Спека прошла ревью пяти ролей участников разработки. Итоги и решения по каждой
находке — раздел 5.

## 2. Находки аудита

Аудит 17.11.2026: 149 файлов в трекере, 54 файла Kotlin, 6 011 строк `src/main`,
2 694 строки тестов. Метрики длины строк считаны по символам, не по байтам:
кириллица в UTF-8 занимает два байта, поэтому счёт по байтам завышает число.

| № | Находка | Факт | Приоритет | Где чиним |
|---|---|---|---|---|
| 1 | Нет лицензии | GitHub API: `license: None`, `visibility: public` | P1 | R1, R2 |
| 2 | Стиль Kotlin не проверяется | нет ktlint, spotless, detekt, `.editorconfig`; импорты не отсортированы в 13 файлах; 11 строк длиннее 120 символов (344, если считать байты); один двойной KDoc | P1 | R3, R4 |
| 3 | Нет release-сборки | нет `signingConfigs`, `minifyEnabled`, proguard; CI собирает только debug | P1 | R5, R6 |
| 4 | README не показывает продукт | нет скриншота, статуса, структуры, требований, раздела лицензии | P2 | R7 |
| 5 | Внутренние следы | `README.md:13` ссылается на внутренний эпик; `docs/spec.md` — личный путь и id проекта в двух местах | P2 | R7, R8 |
| 6 | `.gitignore` смешивает проект и процесс | `.review-delta.txt` — артефакт ревью; нет `.idea/`, `*.iml`, `.DS_Store`, шаблонов ключей | P2 | R9 |
| 7 | Разнобой названий | репозиторий `receipt-split-calculator`, Gradle `chekcalc`, пакет `ru.chekcalc`, ярлык «Расчёт по чеку» | P3 | R7 (таблица имён) |
| 8 | Строки не в ресурсах | ≈200 литералов с кириллицей в `ui/` и 12 в `MainActivity.kt`, `strings.xml` нет | P2 | отдельная задача |
| 9 | Крупные файлы | `ReviewScreen.kt` — 961 строка, `MainActivity.kt` — 492, навигация в Activity | P2 | отдельная задача |
| 10 | Темы коммитов | 6 из 34 — транслит, обрезанный на 72 символах | P3 | вне кода: squash-merge |
| 11 | Ветки и PR | 8 веток `task-*` на remote, PR #5 (бэкенд в Android-репозитории), 2 PR Dependabot | P3 | отдельная уборка |
| 12 | Нет шаблонов и журнала | нет issue/PR-шаблонов, `SECURITY.md`, `CHANGELOG`, тегов | P3 | отдельная задача |
| 13 | Скриншоты по номерам задач | `docs/screenshots/2321/`, `docs/screenshots/2328/` | P3 | отдельная задача |
| 14 | Закрепление actions непоследовательно | `upload-artifact` по SHA, остальные по тегу; `haiplane` — по `main` в workflow с ключом записи | P2 | R6 |
| 15 | Golden set дублирует список | `GoldenSetTest` перечисляет 21 слаг руками; новый эталон молча не проверяется | P2 | R10 |
| 16 | Пакеты тестов не зеркалят main | `ru.chekcalc.review` вместо `ru.chekcalc.ui.review`, 5 файлов | P3 | отдельная задача |

## 3. Решения

**D1. Лицензия MIT, правообладатель `agentdrover`, год 2026.**
Личная публичная разработка без планов на закрытие кода. MIT разрешает
использование, изменение и распространение, требует сохранить уведомление об
авторских правах и не даёт гарантий. Apache-2.0 добавил бы патентный грант,
который личному проекту без патентов не нужен.

**D2. Граница лицензии: код — MIT, фото эталонных чеков — по своим лицензиям.**
В наборе 21 фото из Wikimedia Commons и CORD v2 (CC BY, CC BY-SA, CC0, public
domain). MIT в корне не может их перелицензировать. Граница описана в `NOTICE`
и в разделе «Лицензия» README; атрибуция остаётся в `ATTRIBUTION.md`.

**D3. Формат — ktlint через Gradle-плагин, настройки в `.editorconfig`.**
Плагин `org.jlleitschuh.gradle.ktlint` даёт задачи `ktlintCheck` и `ktlintFormat`
и читает `.editorconfig`, поэтому правила не дублируются в Gradle DSL. Плагин
не фиксирует движок, поэтому версия ktlint задаётся явно в DSL.

**D4. Единый стиль: `ktlint_official`, отступ 4, LF, UTF-8, финальный перевод
строки, длина строки 120.**
Compose-функции с заглавной буквы — не нарушение: правило имени функции
отключено для аннотации `Composable`. Имена тестов в обратных кавычках не
ограничены по длине. Строки, состоящие только из строкового литерала, ktlint не
переносит: длинный текст уходит в ресурсы отдельной задачей.

**D5. Release без R8.**
`buildTypes.release` получает `signingConfig` из переменных окружения и
`proguard-rules.pro`. `minifyEnabled` остаётся `false`: включить сжатие и не
проверить его на устройстве нельзя, а ошибка проявится только в рантайме.

**D6. Подпись — «всё или ничего».**
Задана любая из четырёх переменных — обязаны быть все, и файл ключа должен
существовать; иначе конфигурация падает с объяснением. Никаких тихих откатов в
неподписанный APK: подпись нельзя «не заметить».

**D7. Подпись проверяется в CI на эфемерном ключе.**
Секреты владельца для этого не нужны: ключ генерируется внутри прогона,
хранится в `$RUNNER_TEMP` и не покидает runner. Прогон собирает подписанный APK
и проверяет его `apksigner verify`. Так проверка подписи повторяема, а не разовая.

**D8. Стиль проверяется до тестов, но не глушит отчёт хаба.**
`ktlintCheck` идёт первым: формат падает за секунды. Остальные шаги объявлены
`if: always()`, поэтому падение стиля не оставит хаб без результатов `tests` и
`lint`. Имена проверок контракта (`lint`, `tests`) сохранены, `ktlint`
добавлен отдельным именем.

## 4. Требования

### R1. LICENSE

- Файл `LICENSE` в корне: стандартный текст MIT, строка
  `Copyright (c) 2026 agentdrover`.
- Проверка: `git ls-files LICENSE`, первые строки файла.

### R2. NOTICE

- Файл `NOTICE` в корне: код — MIT, фото набора — по лицензиям из
  `ATTRIBUTION.md`, ссылка на оба файла.
- README в разделе «Лицензия» ссылается на `LICENSE`, `NOTICE` и
  `ATTRIBUTION.md`.

### R3. `.editorconfig`

- `root = true`; общие правила: UTF-8, LF, финальный перевод строки, отступ 4,
  обрезка хвостовых пробелов.
- Секция `[*.{kt,kts}]`: `max_line_length = 120`, `ktlint_code_style =
  ktlint_official`, `ktlint_function_naming_ignore_when_annotated_with =
  Composable`, `ktlint_ignore_back_ticked_identifier = true`.
- Файл лежит в корне репозитория и действует на `app/`.

### R4. ktlint в сборке

- `app/build.gradle.kts`: плагин `org.jlleitschuh.gradle.ktlint` версии
  `12.1.1` и явная версия движка `ktlint { version.set("1.3.1") }`.
- Разовый `ktlintFormat` по проекту; `ktlintCheck` зелёный.
- Обновление ktlint — отдельный коммит с прогоном `ktlintFormat` и `ktlintCheck`.

### R5. Release-сборка

- `signingConfigs` объявлен до `buildTypes`; значения читаются из
  `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`.
- Гейт D6: частичный набор переменных или отсутствующий файл — ошибка
  конфигурации с внятным текстом.
- `buildTypes.release`: `isMinifyEnabled = false`, `proguardFiles` с
  `proguard-rules.pro`, `signingConfig` — только при полном наборе переменных.
- Путь к keystore — абсолютный: относительный резолвится от `app/`, не от корня.
- Без переменных собирается `app/build/outputs/apk/release/chekcalc-release-unsigned.apk`;
  с переменными — `chekcalc-release.apk`.

### R6. CI

- `.github/workflows/android.yml`: шаг стиля `id: ktlint` перед тестами, шаги
  `tests` и `lint` сохраняют свои `id` и получают `if: always()`;
  `assembleDebug` и release-сборка — тоже `always()`.
- Шаг «Release с тестовым ключом»: `keytool -genkeypair` в `$RUNNER_TEMP`,
  `assembleRelease`, `apksigner verify --print-certs`.
- Все `uses:` закреплены по полному SHA с версией в комментарии: `actions/checkout`,
  `actions/setup-java`, `gradle/actions/setup-gradle`, `upload-artifact`,
  `agentdrover/haiplane/...` в обоих workflow. Обновление — руками, с прогоном.
- `on: push` ограничен ветками `main` и `task-*/**`; `pull_request` — без
  фильтров. Добавлен `concurrency` с отменой устаревших прогонов.
  `paths-ignore` не добавляется: workflow — единственный источник required
  checks и отчёта хаба.
- `timeout-minutes` поднят с 20 до 25: добавлены два шага.

### R7. README

Разделы: что делает продукт и скриншот; статус; требования; сборка, проверки и
release с таблицей переменных подписи; установка; первый запуск (провайдер и
ключ); структура репозитория; таблица названий; лицензия и границы.

- Скриншот — `docs/screenshots/home-light.png`, путь без внутренних номеров.
- Внутренних ссылок и личных путей нет.
- `app/README.md` дополняется командами `check`, `format` и release-сборки.

### R8. `docs/spec.md`

Убрать личный путь и id проекта, оставить имя проекта и репозитория.

### R9. `.gitignore`

Добавить `.idea/`, `*.iml`, `.DS_Store`, `*.jks`, `*.keystore`,
`keystore.properties`, `*.p12`, `*.pfx`, `release-key*`.
Убрать `.review-delta.txt` — артефакт процесса, а не проекта.
Инструментальные каталоги (`.codex-home/`, `.uv-cache/`, `.review-diff.txt`)
остаются в локальном `.git/info/exclude`: в проектном `.gitignore` им не место.

### R10. Golden set

`GoldenSetTest` находит наборы обходом каталога ресурсов `golden/`, а не списком
слагов. Требования: каталог ресурсов распакован (`protocol == "file"`), набор не
пуст, у каждого набора есть `expected.json` и `photo.jpg`.

### R11. Make-цели

`app/Makefile`: `format` → `ktlintFormat`, `check` → `ktlintCheck`, затем
`test` двумя вызовами. Обе цели в `.PHONY`. Существующие `analyze`, `test`,
`build`, `clean` не меняются.

## 5. Ревью по ролям

Ревью проведено до правок: пять ролей прочитали версию 1.0 и репозиторий,
каждая дала находки с приоритетом. Ниже — что вошло в версию 1.1.

| Роль | Что проверял | Находки | Решение |
|---|---|---|---|
| Android-разработчик | Совместимость плагина с AGP 8.5.2, Gradle 8.7, Kotlin 2.0.21; поведение `ktlintFormat`; подпись; golden set | 8 (P1: 3) | Версии закреплены (D3, R4); свойства `ktlint_*` добавлены (R3); «дифф только импорты» заменено на явное требование проверять дифф; гейт подписи «всё или ничего» (D6); golden set читает каталог (R10). Отложено: `.git-blame-ignore-revs` |
| CI/DevOps | Бюджет прогона, отчёт хаба, пиннинг, дубли прогонов | 8 (P1: 2) | Таймаут 25 минут и общий прогон сборок (R6); `if: always()` и отдельный `id: ktlint` (D8); эфемерный ключ вместо противоречия «секретов нет — подпись есть» (D7); все `uses:` по SHA (R6); `concurrency` и фильтр веток (R6). Отложено: передача `ran-commands` отчёту |
| QA | Трассируемость требований и критериев, воспроизводимость проверок | 8 (P1: 3) | Добавлены критерии на `.editorconfig`, `docs/spec.md`, пиннинг, golden set, Make-цели; baseline тестов — 452 записи из `test-results`, а не «163»; рецепт тестового ключа — в CI и в разделе 9; проверка «keystore не в git» добавлена |
| Безопасность | Ключи, keystore, supply chain, границы лицензий, публичность | 8 (P1: 2) | Подпись проверяется в CI (D7); расширены шаблоны `.gitignore` и запрет паролей в отслеживаемых файлах (R5, R9); `NOTICE` и граница лицензий (D2); внутренние следы убраны из README и спеки (R7, R8). Отложено: `.gitleaks.toml`, pre-commit, branch protection |
| Техписатель | README и спека глазами внешнего читателя, точность чисел | 8 (P1: 2) | Из спеки убраны внутренние номера и личный путь; README получил требования, первый запуск, установку, ограничения и таблицу названий (R7); числа пересчитаны по символам (раздел 2); «спека прошла ревью» стало правдой (этот раздел). Отложено: английский блок в README |

## 6. Границы

**Входит:** R1–R11.

**Не входит (отдельные задачи):**

1. Вынос ≈200 русских литералов в `strings.xml`.
2. Разделение `ReviewScreen.kt` и навигация.
3. R8/minify и `shrinkResources`.
4. Уборка 8 веток `task-*` и PR #5.
5. Шаблоны issue и PR, `SECURITY.md`, `CHANGELOG`, теги.
6. Переименование каталогов `docs/screenshots/<task-id>`.
7. Перенос тестовых пакетов под `ui/`.
8. Настройка squash-merge для тем коммитов.
9. `.gitleaks.toml` и pre-commit на секреты.
10. Защита `main` и required reviewers на environment с ключом записи.
11. `verification-metadata` и dependency locking для Gradle.
12. Английский блок в README.

## 7. Приёмка

1. `LICENSE` с MIT и правообладателем; `NOTICE` с границей лицензий.
2. `ktlintCheck` зелёный; `.editorconfig` реально применяется.
3. Тесты зелёные: 452 записи в `test-results` (debug и release), 0 падений.
4. `assembleRelease` без переменных даёт `chekcalc-release-unsigned.apk`.
5. Release с тестовым ключом подписан, `apksigner verify` проходит; ключ не в git.
6. CI содержит шаги `ktlintCheck` и подписанной release-сборки; прогон зелёный.
7. README со скриншотом, статусом, структурой и лицензией; путь картинки
   существует; внутренних ссылок нет.
8. `.gitignore` игнорирует `.idea/`, `*.iml`, `.DS_Store`, шаблоны ключей;
   `.review-delta.txt` не игнорируется правилом репозитория и не отслеживается.
9. `docs/spec.md` без личного пути и id проекта.
10. Все `uses:` в workflow закреплены по SHA.
11. Golden set находит все каталоги и падает на каталоге без `expected.json`.
12. `make -n -C app check format` показывает `ktlintCheck`, `test`, `ktlintFormat`.
13. Спека содержит разделы аудита, решений, ревью ролей, границ и приёмки.

## 8. Риски

| Риск | Степень | Что делаем |
|---|---|---|
| Переформат конфликтует с открытыми PR Dependabot #25 и #26 | средняя | Слить переформат первым, PR перегенерировать после merge |
| Новый плагин требует сети и замедляет CI | средняя | Плагин из plugins.gradle.org, кэш `setup-gradle` включён, таймаут поднят |
| Секреты подписи попадают в репозиторий или лог | высокая | Шаблоны ключей в `.gitignore`, пароли только в env, проверка `git ls-files` |
| ktlint конфликтует с форматированием IDE на Compose | низкая | Версии закреплены, правило имени функции отключено для `Composable` |
| Пиннинг `haiplane` замораживает протокол отчёта | средняя | Обновление SHA — отдельный коммит с прогоном и проверкой карточки |
| Release без R8 даёт APK больше ожидаемого | низкая | Записано в README, R8 — отдельная задача |

## 9. Проверки

```sh
git ls-files LICENSE NOTICE .editorconfig
head -3 LICENSE
cd app && ./gradlew --no-daemon ktlintCheck
make -C app check
make -C app analyze
cd app && ./gradlew --no-daemon assembleDebug assembleRelease
git check-ignore -v .idea/ app/app.iml .DS_Store keystore.properties
git ls-files | grep -Ei '\.(jks|keystore)$'    # ожидается пусто
! grep -rnE 'my_projects|id 17|/Users/|/opt/homebrew' README.md docs/spec.md
! grep -rnE 'uses: [^#]*@(main|master|v[0-9]+(\.[0-9]+)*)$' .github/workflows
```

Подпись локально:

```sh
keytool -genkeypair -keystore "$HOME/ci-test.jks" -alias ci-test \
  -storepass changeit -keypass changeit -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Test release, O=receipt-split-calculator"
export ANDROID_KEYSTORE_PATH="$HOME/ci-test.jks"
export ANDROID_KEYSTORE_PASSWORD=changeit
export ANDROID_KEY_ALIAS=ci-test
export ANDROID_KEY_PASSWORD=changeit
cd app && ./gradlew --no-daemon assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  build/outputs/apk/release/chekcalc-release.apk
unset ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
```

Локальный прогон требует JDK 17 и Android SDK 35. Тестовый ключ создаётся вне
репозитория и удаляется после проверки.

## 10. Открытые вопросы

1. Заводить ли секреты подписи релиза в GitHub. До решения `assembleRelease`
   в CI проверяет только эфемерный ключ; боевой ключ — дело владельца.
2. Нужен ли `CHANGELOG.md` до первого тега.
3. Нужен ли английский блок в README для внешней аудитории.
4. Нужна ли защита `main` и обязательный review — настройка GitHub, не репозитория.
