#!/usr/bin/env bash
# Релизный конвейер приложения «Расчёт по чеку».
#
# Подкоманды:
#   check   — проверки без ключа: чистое дерево, версия, ktlint, тесты, lint
#   verify  — SHA входит в main, зелёный CI на нём, состояние тега, правил, релиза
#   build   — подписанный release APK, сверка сертификата и файл доказательств
#   draft   — тег на проверенный SHA и ЧЕРНОВИК GitHub Release со сверкой файла
#
# Публикация отдельная и явная: gh release edit <tag> --draft=false.
# Ключ остаётся локальным: переменные ANDROID_KEYSTORE_* или связка ключей macOS.
set -euo pipefail
trap 'rc=$?; printf "ОШИБКА: команда завершилась с кодом %s на строке %s\n" "$rc" "$LINENO" >&2' ERR

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
readonly APP_DIR="$REPO_ROOT/app"
readonly VERSION_FILE="$APP_DIR/version.properties"
readonly EVIDENCE_FILE="$APP_DIR/build/release-evidence.json"
readonly RELEASE_DIR="$APP_DIR/build/outputs/apk/release"
readonly PACKAGE_NAME="ru.chekcalc"
readonly EXPECTED_CERT_SHA256="79bf65fa1ece5c9d3f04eef8e807689c4349eef797032e41e85a123f71d3577f"
readonly KEYCHAIN_SERVICE="chekcalc-release"
readonly KEYCHAIN_ACCOUNT="keystore"
readonly KEY_ALIAS="chekcalc"

info() { printf '  %s\n' "$*"; }
die() { printf 'ОШИБКА: %s\n' "$*" >&2; exit 1; }

setup_java() {
    if command -v java >/dev/null 2>&1; then
        return 0
    fi
    local candidate
    for candidate in \
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
        "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            export PATH="$candidate/bin:$PATH"
            return 0
        fi
    done
    die "java не найден. Установите JDK 17 и задайте JAVA_HOME"
}

setup_android_sdk() {
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        return 0
    fi
    local sdk_dir
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$APP_DIR/local.properties" 2>/dev/null | tail -1)"
    [ -n "$sdk_dir" ] || die "SDK не найден: задайте ANDROID_HOME или sdk.dir в app/local.properties"
    export ANDROID_HOME="$sdk_dir"
}

build_tool() {
    local tool="$1" newest
    newest="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name "$tool" | sort -V | tail -1)"
    [ -n "$newest" ] || die "нет $tool в $ANDROID_HOME/build-tools"
    printf '%s' "$newest"
}

load_version() {
    [ -f "$VERSION_FILE" ] || die "нет $VERSION_FILE"
    VERSION_CODE="$(sed -n 's/^versionCode=//p' "$VERSION_FILE" | tr -d '[:space:]')"
    VERSION_NAME="$(sed -n 's/^versionName=//p' "$VERSION_FILE" | tr -d '[:space:]')"
    [ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || die "versionCode и versionName должны быть в $VERSION_FILE"
    case "$VERSION_CODE" in
        '' | *[!0-9]*) die "versionCode должен быть целым числом, сейчас: $VERSION_CODE" ;;
    esac
    TAG="v$VERSION_NAME"
}

require_clean_tree() {
    if ! git -C "$REPO_ROOT" diff --quiet; then
        die "в дереве есть несохранённые правки (unstaged). Закоммитьте или уберите их"
    fi
    if ! git -C "$REPO_ROOT" diff --cached --quiet; then
        die "в индексе есть изменения (staged). Закоммитьте или уберите их"
    fi
    local untracked
    untracked="$(git -C "$REPO_ROOT" ls-files --others --exclude-standard | head -3)"
    if [ -n "$untracked" ]; then
        die "в дереве есть неотслеживаемые файлы: $untracked"
    fi
}

# Максимальный versionCode среди выпущенных тегов v*. Теги обновляются с origin:
# локальная копия может отставать, и тогда рост версии проверялся бы не по той базе.
max_released_version_code() {
    git -C "$REPO_ROOT" fetch origin --tags --quiet 2>/dev/null ||
        die "не удалось обновить теги с origin"
    local tag code max=0
    while IFS= read -r tag; do
        [ -n "$tag" ] || continue
        code="$(git -C "$REPO_ROOT" show "$tag:app/version.properties" 2>/dev/null |
            sed -n 's/^versionCode=//p' | tr -d '[:space:]')"
        case "$code" in
            '' | *[!0-9]*) continue ;;
        esac
        [ "$code" -gt "$max" ] && max="$code"
    done <<EOF
$(git -C "$REPO_ROOT" tag -l 'v*')
EOF
    printf '%s' "$max"
}

assert_version_growth() {
    local max
    max="$(max_released_version_code)"
    if [ "$max" -gt 0 ]; then
        [ "$VERSION_CODE" -gt "$max" ] ||
            die "versionCode $VERSION_CODE не вырос относительно выпущенного максимума ($max)"
        info "версия выросла: $max → $VERSION_CODE"
    else
        info "выпущенных тегов нет: первый выпуск"
    fi
}

command_check() {
    setup_java
    setup_android_sdk
    load_version
    info "версия из файла: $VERSION_NAME (versionCode $VERSION_CODE), тег $TAG"
    require_clean_tree
    assert_version_growth

    local tracked_apk
    tracked_apk="$(git -C "$REPO_ROOT" ls-files '*.apk' | head -3)"
    [ -z "$tracked_apk" ] || die "APK попал в git: $tracked_apk. Уберите его из индекса"

    info "тесты релизных скриптов"
    (cd "$REPO_ROOT" && python3 -m unittest discover -s tests -p 'test_*.py' -q)

    info "ktlint, тесты, lint"
    (cd "$APP_DIR" && ./gradlew --quiet ktlintCheck test lint)
    info "release-check пройден"
}

github_repo() { printf '%s' "$(gh repo view --json nameWithOwner --jq .nameWithOwner)"; }

# Запрос к API с явным контролем: только подтверждённый 404 считается
# отсутствием объекта, любой другой ответ — отказ, а не «нет данных».
api_call() {
    local path="$1" raw status
    raw="$(gh api -i "$path" 2>&1)" || true
    status="$(printf '%s' "$raw" | head -1 | grep -oE '[0-9]{3}' | head -1)"
    [ -n "$status" ] || die "GitHub API не ответил на $path"
    case "$status" in
        200 | 201 | 204)
            API_STATUS="$status"
            API_BODY="$(printf '%s' "$raw" | awk 'body { print } /^\r?$/ { body = 1 }')"
            ;;
        404)
            API_STATUS=404
            API_BODY=""
            ;;
        *)
            die "GitHub API ответил $status на $path"
            ;;
    esac
}

json_field() { python3 -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$1" "$2"; }

# Тег на origin, разыменованный до коммита. Пустой ответ — тега нет.
remote_tag_sha() {
    local out peeled
    out="$(git -C "$REPO_ROOT" ls-remote --tags origin "refs/tags/$TAG" "refs/tags/$TAG^{}" 2>/dev/null)" || return 2
    peeled="$(printf '%s' "$out" | awk '/\^\{\}$/ { print $1; exit }')"
    if [ -n "$peeled" ]; then
        printf '%s' "$peeled"
        return 0
    fi
    printf '%s' "$out" | awk -v ref="refs/tags/$TAG" '$2 == ref { print $1; exit }'
}

# Оценка правил тегов через scripts/tag_rules_gate.py.
# Коды: 0 — можно, 3 — запрещено, другое — не удалось оценить.
tag_rules_gate() {
    local uid ruleset_ids ruleset_id collected=""
    uid="$(gh api user --jq .id 2>/dev/null)" || return 2
    api_call "repos/$(github_repo)/rulesets" || return 2
    ruleset_ids="$(printf '%s' "$API_BODY" | python3 -c '
import json, sys
for item in json.load(sys.stdin):
    if item.get("target") == "tag":
        print(item["id"])
')" || return 2
    for ruleset_id in $ruleset_ids; do
        api_call "repos/$(github_repo)/rulesets/$ruleset_id" || return 2
        collected+="$API_BODY"$'\n'
    done
    printf '%s' "$collected" | python3 "$SCRIPT_DIR/tag_rules_gate.py" --ref "refs/tags/$TAG" --uid "$uid"
}

command_verify() {
    setup_java
    load_version
    require_clean_tree
    local sha="${1:-$(git -C "$REPO_ROOT" rev-parse HEAD)}"
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die "нужен полный SHA (40 hex), получено: $sha"

    git -C "$REPO_ROOT" fetch origin main --quiet 2>/dev/null ||
        die "не удалось обновить origin/main"
    git -C "$REPO_ROOT" merge-base --is-ancestor "$sha" origin/main ||
        die "SHA $sha не входит в main: релизить можно только слитое"
    info "SHA входит в main: $sha"

    local runs latest_state
    runs="$(gh run list --workflow android.yml --commit "$sha" --limit 40 \
        --json status,conclusion,createdAt,event,headBranch,databaseId 2>/dev/null)" ||
        die "не удалось получить прогоны android.yml для $sha"
    latest_state="$(printf '%s' "$runs" | python3 -c '
import json, sys
runs = json.load(sys.stdin)
if not runs:
    print("none;")
    raise SystemExit(0)
latest = max(runs, key=lambda run: run["createdAt"])
print("%s;%s;%s" % (latest["status"], latest["conclusion"], latest["databaseId"]))
')"
    case "$latest_state" in
        none\;*) die "на SHA $sha нет прогонов android.yml" ;;
        completed\;success\;*) info "последний прогон android.yml на SHA: успешен" ;;
        completed\;*)
            die "последний прогон android.yml на SHA завершился с ${latest_state#completed;}"
            ;;
        *)
            die "последний прогон android.yml на SHA ещё не завершён: ${latest_state%%;*}"
            ;;
    esac

    local tag_sha="" rc=0
    tag_sha="$(remote_tag_sha)" || rc=$?
    [ "$rc" -eq 0 ] || die "не удалось прочитать тег $TAG на origin"
    if [ -n "$tag_sha" ]; then
        [ "$tag_sha" = "$sha" ] ||
            die "тег $TAG на origin стоит на $tag_sha, а не на $sha. Тег не перемещается"
        info "тег $TAG уже на этом SHA: возобновление выпуска"
    else
        info "тега $TAG нет: новый выпуск"
    fi

    api_call "repos/$(github_repo)/releases/tags/$TAG"
    if [ "$API_STATUS" = "200" ]; then
        [ "$(json_field "$API_BODY" draft)" = "True" ] ||
            die "релиз $TAG уже опубликован: новый выпуск с тем же тегом невозможен"
        info "черновик релиза $TAG уже существует: возобновление"
    else
        info "релиза $TAG нет"
    fi

    local gate_rc=0
    tag_rules_gate || gate_rc=$?
    case "$gate_rc" in
        0) info "правила репозитория не мешают созданию тега" ;;
        3) die "правила репозитория запрещают создание тега для вашей учётной записи" ;;
        *) die "не удалось оценить правила репозитория (код $gate_rc)" ;;
    esac
    printf 'VERIFY_OK sha=%s\n' "$sha"
}

load_signing_key() {
    if [ -n "${ANDROID_KEYSTORE_PATH:-}" ]; then
        : "${ANDROID_KEYSTORE_PASSWORD:?нет ANDROID_KEYSTORE_PASSWORD}"
        : "${ANDROID_KEY_ALIAS:=$KEY_ALIAS}"
        : "${ANDROID_KEY_PASSWORD:=$ANDROID_KEYSTORE_PASSWORD}"
        export ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
        info "ключ из окружения: $ANDROID_KEYSTORE_PATH"
        return 0
    fi
    command -v security >/dev/null 2>&1 || die "нет ключа: задайте ANDROID_KEYSTORE_PATH"
    local password
    password="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" -w 2>/dev/null)" ||
        die "нет ключа: переменные ANDROID_KEYSTORE_* не заданы, в связке ключей нет $KEYCHAIN_SERVICE"
    export ANDROID_KEYSTORE_PATH="$HOME/.android-keys/chekcalc-release.jks"
    export ANDROID_KEYSTORE_PASSWORD="$password"
    export ANDROID_KEY_ALIAS="$KEY_ALIAS"
    export ANDROID_KEY_PASSWORD="$password"
    [ -f "$ANDROID_KEYSTORE_PATH" ] || die "файл ключа не найден: $ANDROID_KEYSTORE_PATH"
    info "ключ из связки ключей: $ANDROID_KEYSTORE_PATH"
}

command_build() {
    local clean_first="${1:-}"
    setup_java
    setup_android_sdk
    load_version
    require_clean_tree
    load_signing_key

    local stale=""
    if [ -d "$RELEASE_DIR" ]; then
        stale="$(find "$RELEASE_DIR" -maxdepth 1 -name '*.apk' | head -3)"
    fi
    if [ -n "$stale" ]; then
        if [ "$clean_first" = "--clean" ]; then
            rm -f "$RELEASE_DIR"/*.apk "$RELEASE_DIR"/*.sha256
            info "удалён устаревший APK"
        else
            die "в $RELEASE_DIR уже лежит APK. Запустите с --clean или очистите вручную"
        fi
    fi

    info "сборка release"
    (cd "$APP_DIR" && ./gradlew --no-daemon assembleRelease)

    local apk="$RELEASE_DIR/chekcalc-release.apk"
    [ -f "$apk" ] || die "APK не появился: $apk"

    local apksigner cert_sha
    apksigner="$(build_tool apksigner)"
    cert_sha="$("$apksigner" verify --print-certs "$apk" |
        sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')"
    [ -n "$cert_sha" ] || die "не удалось прочитать отпечаток сертификата"
    [ "$cert_sha" = "$EXPECTED_CERT_SHA256" ] ||
        die "APK подписан другим сертификатом: $cert_sha, ожидался $EXPECTED_CERT_SHA256"
    info "сертификат совпал: $EXPECTED_CERT_SHA256"

    local aapt2 badging
    aapt2="$(build_tool aapt2)"
    badging="$("$aapt2" dump badging "$apk")"
    printf '%s\n' "$badging" | grep -q "package: name='$PACKAGE_NAME' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" ||
        die "манифест не совпал: ожидались $PACKAGE_NAME / $VERSION_CODE / $VERSION_NAME"
    if printf '%s\n' "$badging" | grep -q 'application-debuggable'; then
        die "APK собран debuggable: так выпускать нельзя"
    fi
    info "манифест: $PACKAGE_NAME $VERSION_CODE $VERSION_NAME, не debuggable"

    local apk_sha sha_file
    apk_sha="$(shasum -a 256 "$apk" | awk '{print $1}')"
    sha_file="$apk.sha256"
    printf '%s  %s\n' "$apk_sha" "$(basename "$apk")" >"$sha_file"
    info "sha256 APK: $apk_sha"

    local sha
    sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    mkdir -p "$(dirname "$EVIDENCE_FILE")"
    python3 - "$EVIDENCE_FILE" "$sha" "$VERSION_CODE" "$VERSION_NAME" "$cert_sha" "$apk_sha" "$apk" <<'PY'
import datetime, json, pathlib, sys
path, sha, code, name, cert, apk_sha, apk = sys.argv[1:8]
data = {
    "sha": sha,
    "versionCode": int(code),
    "versionName": name,
    "certSha256": cert,
    "apkSha256": apk_sha,
    "apkPath": apk,
    "builtAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
}
pathlib.Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
    info "доказательства: $EVIDENCE_FILE"
    printf 'BUILD_OK apk=%s sha256=%s\n' "$apk" "$apk_sha"
}

command_draft() {
    local dry_run=""
    [ "${1:-}" = "--dry-run" ] && dry_run="1"
    setup_java
    load_version
    require_clean_tree
    [ -f "$EVIDENCE_FILE" ] || die "нет $EVIDENCE_FILE: сначала release-build"
    [ -f "$REPO_ROOT/docs/releases/$TAG.md" ] || die "нет заметок docs/releases/$TAG.md"

    local sha apk apk_sha head
    sha="$(json_field "$(cat "$EVIDENCE_FILE")" sha)"
    apk="$(json_field "$(cat "$EVIDENCE_FILE")" apkPath)"
    apk_sha="$(json_field "$(cat "$EVIDENCE_FILE")" apkSha256)"
    head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    [ "$sha" = "$head" ] || die "доказательства от $sha, а HEAD $head. Пересоберите APK"
    [ -f "$apk" ] || die "APK из доказательств не найден: $apk"
    [ "$(shasum -a 256 "$apk" | awk '{print $1}')" = "$apk_sha" ] ||
        die "sha256 APK не совпал с доказательствами"

    if [ -n "$dry_run" ]; then
        printf 'DRY-RUN: тег %s на %s\n' "$TAG" "$head"
        printf 'DRY-RUN: gh release create %s %s %s.sha256 --draft --verify-tag --notes-file docs/releases/%s.md\n' \
            "$TAG" "$apk" "$apk" "$TAG"
        printf 'DRY-RUN: проверки CI, тега и скачивания файла выполнит настоящий запуск\n'
        return 0
    fi

    # Настоящий запуск повторяет проверки verify: без зелёного CI и без
    # свободного тега черновик не создаётся.
    command_verify "$head"

    if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
        local local_sha
        local_sha="$(git -C "$REPO_ROOT" rev-list -n1 "$TAG")"
        [ "$local_sha" = "$head" ] || die "локальный тег $TAG указывает на $local_sha, а не на $head"
        info "тег $TAG уже на месте"
    else
        git -C "$REPO_ROOT" tag -a "$TAG" -m "Релиз $TAG" "$head"
        info "создан тег $TAG на $head"
    fi

    local remote_sha rc=0
    remote_sha="$(remote_tag_sha)" || rc=$?
    [ "$rc" -eq 0 ] || die "не удалось прочитать тег $TAG на origin"
    if [ -z "$remote_sha" ]; then
        git -C "$REPO_ROOT" push origin "refs/tags/$TAG"
        info "тег отправлен в origin"
    elif [ "$remote_sha" != "$head" ]; then
        die "на origin тег $TAG стоит на $remote_sha, а не на $head. Тег не перемещается"
    else
        info "тег $TAG уже на origin и указывает на $head"
    fi

    api_call "repos/$(github_repo)/releases/tags/$TAG"
    if [ "$API_STATUS" = "200" ]; then
        [ "$(json_field "$API_BODY" draft)" = "True" ] ||
            die "релиз $TAG уже опубликован: файлы не трогаем"
        info "черновик $TAG уже существует, файлы не трогаем"
    else
        (cd "$REPO_ROOT" && gh release create "$TAG" "$apk" "$apk.sha256" \
            --draft --verify-tag --title "Расчёт по чеку $TAG" \
            --notes-file "docs/releases/$TAG.md")
        info "создан черновик релиза $TAG"
    fi

    local tmp_dir downloaded
    tmp_dir="$(mktemp -d)"
    if ! gh release download "$TAG" --repo "$(github_repo)" \
        --pattern 'chekcalc-release.apk' --dir "$tmp_dir"; then
        rm -rf "$tmp_dir"
        die "не удалось скачать APK из черновика $TAG"
    fi
    downloaded="$(shasum -a 256 "$tmp_dir/chekcalc-release.apk" | awk '{print $1}')"
    rm -rf "$tmp_dir"
    [ "$downloaded" = "$apk_sha" ] ||
        die "скачанный из релиза APK не совпал по sha256: $downloaded против $apk_sha"
    info "скачанный APK совпал по sha256"
    printf 'DRAFT_OK tag=%s draft=yes\n' "$TAG"
}

usage() {
    cat <<'TXT'
Использование: scripts/release.sh <команда> [аргументы]

  check             проверки без ключа: дерево, версия, ktlint, тесты, lint
  verify [SHA]      SHA в main, последний CI на нём зелёный, тег, правила, релиз
  build [--clean]   подписанный release APK и файл доказательств
  draft [--dry-run] тег на проверенный SHA и черновик GitHub Release
TXT
}

main() {
    local command="${1:-}"
    shift || true
    case "$command" in
        check) command_check ;;
        verify) command_verify "${1:-}" ;;
        build) command_build "${1:-}" ;;
        draft) command_draft "${1:-}" ;;
        *) usage; exit 2 ;;
    esac
}

main "$@"
