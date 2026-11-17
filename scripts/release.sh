#!/usr/bin/env bash
# Релизный конвейер приложения «Расчёт по чеку».
#
# Подкоманды:
#   check   — проверки без ключа: чистое дерево, версия из файла, ktlint, тесты, lint
#   verify  — SHA входит в main, CI на нём зелёный, состояние тега, правил и релиза
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

# java не в PATH на машине владельца: keytool, apksigner и keytool читают JAVA_HOME.
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

previous_version_code() {
    local previous_tag
    previous_tag="$(git -C "$REPO_ROOT" describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
    [ -n "$previous_tag" ] || return 0
    git -C "$REPO_ROOT" show "$previous_tag:app/version.properties" 2>/dev/null |
        sed -n 's/^versionCode=//p' | tr -d '[:space:]'
}

command_check() {
    setup_java
    setup_android_sdk
    load_version
    info "версия из файла: $VERSION_NAME (versionCode $VERSION_CODE), тег $TAG"
    require_clean_tree

    local previous
    previous="$(previous_version_code)"
    if [ -n "$previous" ]; then
        [ "$VERSION_CODE" -gt "$previous" ] ||
            die "versionCode $VERSION_CODE не вырос относительно прошлого релиза ($previous)"
        info "версия выросла: $previous → $VERSION_CODE"
    else
        info "прошлых тегов нет: первый выпуск"
    fi

    local tracked_apk
    tracked_apk="$(git -C "$REPO_ROOT" ls-files '*.apk' | head -3)"
    [ -z "$tracked_apk" ] || die "APK попал в git: $tracked_apk. Уберите его из индекса"

    info "ktlint, тесты, lint"
    (cd "$APP_DIR" && ./gradlew --quiet ktlintCheck test lint)
    info "release-check пройден"
}

github_repo() {
    printf '%s' "$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
}

resolve_tag_sha() {
    local tag="$1" ref_json object_type object_sha
    ref_json="$(gh api "repos/$(github_repo)/git/ref/tags/$tag" 2>/dev/null)" || return 1
    object_type="$(printf '%s' "$ref_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["object"]["type"])')"
    object_sha="$(printf '%s' "$ref_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["object"]["sha"])')"
    if [ "$object_type" = "tag" ]; then
        object_sha="$(gh api "repos/$(github_repo)/git/tags/$object_sha" |
            python3 -c 'import json,sys; print(json.load(sys.stdin)["object"]["sha"])')"
    fi
    printf '%s' "$object_sha"
}

tag_rules_block_creation() {
    local login rulesets_json ruleset_id
    login="$(gh api user --jq .login)"
    rulesets_json="$(gh api "repos/$(github_repo)/rulesets")"
    for ruleset_id in $(printf '%s' "$rulesets_json" |
        python3 -c 'import json,sys
for item in json.load(sys.stdin):
    if item.get("target") == "tag":
        print(item["id"])'); do
        if ! gh api "repos/$(github_repo)/rulesets/$ruleset_id" | python3 -c "
import json, sys
data = json.load(sys.stdin)
if data.get('enforcement') != 'active':
    raise SystemExit(0)
bypass = {item.get('actor_id') for item in data.get('bypass_actors', [])}
for rule in data.get('rules', []):
    if rule.get('type') == 'creation' and $login not in bypass:
        raise SystemExit(1)
raise SystemExit(0)
"; then
            return 0
        fi
    done
    return 1
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

    local runs completed success
    runs="$(gh run list --workflow android.yml --commit "$sha" --limit 20 \
        --json status,conclusion,databaseId)"
    completed="$(printf '%s' "$runs" | python3 -c 'import json,sys; print(sum(1 for r in json.load(sys.stdin) if r["status"]=="completed"))')"
    success="$(printf '%s' "$runs" | python3 -c 'import json,sys; print(sum(1 for r in json.load(sys.stdin) if r["status"]=="completed" and r["conclusion"]=="success"))')"
    [ "$completed" -gt 0 ] || die "на SHA $sha нет завершённых прогонов android.yml"
    [ "$success" -gt 0 ] || die "на SHA $sha нет успешных прогонов android.yml"
    info "CI android.yml: успешных $success из $completed"

    local tag_sha tag_state
    if tag_sha="$(resolve_tag_sha "$TAG")"; then
        [ "$tag_sha" = "$sha" ] || die "тег $TAG уже стоит на $tag_sha, а не на $sha. Тег не перемещается"
        tag_state="resume"
        info "тег $TAG уже на этом SHA: возобновление выпуска"
    else
        tag_state="new"
        info "тега $TAG нет: новый выпуск"
    fi

    local release_json release_state
    if release_json="$(gh release view "$TAG" --json isDraft 2>/dev/null)"; then
        if [ "$(printf '%s' "$release_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["isDraft"])')" != "True" ]; then
            die "релиз $TAG уже опубликован: новый выпуск с тем же тегом невозможен"
        fi
        release_state="draft"
        info "черновик релиза $TAG уже существует: возобновление"
    else
        release_state="none"
        info "релиза $TAG нет"
    fi

    if tag_rules_block_creation; then
        die "правила репозитория запрещают создание тегов и ваша учётная запись не в списке исключений"
    fi
    info "правила репозитория не мешают созданию тега"

    [ "$tag_state" != "resume" ] || [ "$release_state" = "draft" ] || true
    printf 'VERIFY_OK tag=%s release=%s sha=%s\n' "$tag_state" "$release_state" "$sha"
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
import json, sys, datetime, pathlib
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

    local sha apk apk_sha evidence_sha evidence_apk_sha
    sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha"])' "$EVIDENCE_FILE")"
    apk="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apkPath"])' "$EVIDENCE_FILE")"
    apk_sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apkSha256"])' "$EVIDENCE_FILE")"
    evidence_sha="$sha"
    evidence_apk_sha="$apk_sha"

    local head
    head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    [ "$evidence_sha" = "$head" ] || die "доказательства от $evidence_sha, а HEAD $head. Пересоберите APK"
    [ -f "$apk" ] || die "APK из доказательств не найден: $apk"
    local actual_sha
    actual_sha="$(shasum -a 256 "$apk" | awk '{print $1}')"
    [ "$actual_sha" = "$evidence_apk_sha" ] || die "sha256 APK не совпал с доказательствами"

    if [ -n "$dry_run" ]; then
        # Предпросмотр: печатает план и ничего не делает. Проверку «HEAD в main»
        # выполняет только настоящий запуск — до merge её пройти нельзя.
        printf 'DRY-RUN: тег %s на %s\n' "$TAG" "$head"
        printf 'DRY-RUN: gh release create %s %s %s.sha256 --draft --verify-tag --notes-file docs/releases/%s.md\n' \
            "$TAG" "$apk" "$apk" "$TAG"
        printf 'DRY-RUN: проверка «HEAD в main», состояния тега и скачивания файла будет выполнена при реальном запуске\n'
        return 0
    fi

    git -C "$REPO_ROOT" fetch origin main --quiet 2>/dev/null || true
    git -C "$REPO_ROOT" merge-base --is-ancestor "$head" origin/main ||
        die "HEAD $head не входит в main: черновик релиза делается только с main"

    if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
        local local_sha
        local_sha="$(git -C "$REPO_ROOT" rev-list -n1 "$TAG")"
        [ "$local_sha" = "$head" ] || die "локальный тег $TAG указывает на $local_sha, а не на $head"
        info "тег $TAG уже на месте"
    else
        git -C "$REPO_ROOT" tag -a "$TAG" -m "Релиз $TAG" "$head"
        info "создан тег $TAG на $head"
    fi

    if ! git -C "$REPO_ROOT" ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
        git -C "$REPO_ROOT" push origin "refs/tags/$TAG"
        info "тег отправлен в origin"
    fi

    if gh release view "$TAG" >/dev/null 2>&1; then
        info "релиз $TAG уже существует, файлы не трогаем"
    else
        (cd "$REPO_ROOT" && gh release create "$TAG" "$apk" "$apk.sha256" \
            --draft --verify-tag --title "Расчёт по чеку $TAG" \
            --notes-file "docs/releases/$TAG.md")
        info "создан черновик релиза $TAG"
    fi

    local tmp_dir
    tmp_dir="$(mktemp -d)"
    trap 'rm -rf "$tmp_dir"' RETURN
    (cd "$tmp_dir" && gh release download "$TAG" --pattern 'chekcalc-release.apk' --dir .)
    local downloaded
    downloaded="$(shasum -a 256 "$tmp_dir/chekcalc-release.apk" | awk '{print $1}')"
    [ "$downloaded" = "$evidence_apk_sha" ] ||
        die "скачанный из релиза APK не совпал по sha256: $downloaded против $evidence_apk_sha"
    info "скачанный APK совпал по sha256"
    printf 'DRAFT_OK tag=%s draft=yes\n' "$TAG"
}

usage() {
    cat <<'TXT'
Использование: scripts/release.sh <команда> [аргументы]

  check            проверки без ключа: дерево, версия, ktlint, тесты, lint
  verify [SHA]     SHA в main, зелёный CI, состояние тега, правил и релиза
  build [--clean]  подписанный release APK и файл доказательств
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
