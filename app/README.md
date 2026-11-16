# chekcalc — Android

Клиент «Расчёт по чеку»: Kotlin, Jetpack Compose, minSdk 26.

## Что нужно

- JDK 17
- Android SDK: platform 35, build-tools 35.0.0
- Путь к SDK — в `local.properties` (`sdk.dir=...`) или в `ANDROID_HOME`

## Проверки

Из корня репозитория:

```sh
make -C app check     # стиль Kotlin (ktlint) и unit-тесты
make -C app analyze   # Android lint
make -C app build     # debug APK
make -C app format    # автоформат Kotlin по .editorconfig
```

APK появится в `app/build/outputs/apk/debug/`.

## Release

```sh
./gradlew assembleRelease
```

Без переменных подписи получается `build/outputs/apk/release/chekcalc-release-unsigned.apk`.
Подписанный APK требует всех четырёх переменных: `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
При частичном наборе конфигурация падает с объяснением. R8 выключен: правила
сжатия для Compose и kotlinx.serialization проверяются отдельной задачей.

## Тема

Палитра и типографика — из макета `docs/mockups/interface.png`: тёплая бумага
`#F6F1E7`, тёмный текст `#17150F`, зелёный акцент `#14503C`, тёмная тема по системе.
