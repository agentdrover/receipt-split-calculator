# chekcalc — Android

Клиент «Расчёт по чеку»: Kotlin, Jetpack Compose, minSdk 26.

## Что нужно

- JDK 17
- Android SDK: platform 35, build-tools 35.0.0
- Путь к SDK — в `local.properties` (`sdk.dir=...`) или в `ANDROID_HOME`

## Проверки

Из корня репозитория:

```sh
make -C app analyze   # Android lint
make -C app test      # JVM unit-тесты
make -C app build     # debug APK
```

APK появится в `app/build/outputs/apk/debug/`.

## Тема

Палитра и типографика — из макета `docs/mockups/interface.png`: тёплая бумага
`#F6F1E7`, тёмный текст `#17150F`, зелёный акцент `#14503C`, тёмная тема по системе.
