#!/usr/bin/env python3
"""Сбор заметок о релизе из заголовков слитых PR.

Заголовки squash-коммитов в main транслитерированы и обрезаны, поэтому брать их
нельзя. Скрипт идёт от коммитов диапазона к PR через GitHub API, берёт русские
заголовки PR и раскладывает их по меткам.

Метки: release:feature -> «Возможности», release:fix -> «Исправления»,
release:maintenance -> «Обслуживание», release:skip -> исключить.
PR без метки попадает в «Требует разбора», а не в «Возможности».

Коды возврата:
  0  заметки собраны;
  2  неверные аргументы или пустой диапазон;
  3  нет ни одного PR для заметок;
  4  ошибка GitHub API (включая 403 и 429).
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys

GROUP_FEATURE = "Возможности"
GROUP_FIX = "Исправления"
GROUP_MAINTENANCE = "Обслуживание"
GROUP_UNSORTED = "Требует разбора"

# Порядок меток задаёт и порядок разделов, и приоритет при нескольких метках.
LABELS = (
    ("release:feature", GROUP_FEATURE),
    ("release:fix", GROUP_FIX),
    ("release:maintenance", GROUP_MAINTENANCE),
)
SKIP_LABEL = "release:skip"
CONVENTIONAL_PREFIX = re.compile(r"^[a-z]+(?:\([^)]*\))?!?:\s*")
# Хаб добавляет к заголовку PR номер задачи в конце: «текст (#2369)». В заметках
# он лишний: ссылка на PR и так рядом.
TRAILING_TASK = re.compile(r"\s*\(#\d+\)\s*$")


class ApiError(RuntimeError):
    """Ответ GitHub API, который нельзя считать «данных нет»."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def clean_title(title):
    """Снимает префикс feat(task): и служебный номер задачи хаба в конце."""
    without_prefix = CONVENTIONAL_PREFIX.sub("", title or "").strip()
    return TRAILING_TASK.sub("", without_prefix).strip()


def labels_of(pull):
    return {label.get("name") for label in pull.get("labels") or []}


def group_of(pull):
    """Раздел для PR или None, если PR исключён меткой release:skip."""
    labels = labels_of(pull)
    if SKIP_LABEL in labels:
        return None
    for label, group in LABELS:
        if label in labels:
            return group
    return GROUP_UNSORTED


def select_pulls(pulls):
    """Оставляет слитые в main PR без дублей по номеру."""
    selected = {}
    for pull in pulls:
        number = pull.get("number")
        if number is None or pull.get("merged_at") is None:
            continue
        if (pull.get("base") or {}).get("ref") != "main":
            continue
        selected.setdefault(number, pull)
    return [selected[number] for number in sorted(selected)]


def render(prs, tag, date):
    """Markdown заметок или None, если ни одного PR нет. Пустые разделы не печатаются."""
    grouped = {group: [] for _, group in LABELS}
    grouped[GROUP_UNSORTED] = []
    for pull in prs:
        group = group_of(pull)
        if group is not None:
            grouped[group].append(pull)
    if not any(grouped.values()):
        return None

    lines = ["## [%s] — %s" % (tag.lstrip("v"), date), ""]
    for _, group in LABELS:
        if not grouped[group]:
            continue
        lines += ["### %s" % group, ""]
        lines += [
            "- [%s](%s) (#%s)" % (clean_title(pull.get("title")), pull.get("html_url"), pull.get("number"))
            for pull in grouped[group]
        ]
        lines.append("")
    if grouped[GROUP_UNSORTED]:
        lines += [
            "### %s" % GROUP_UNSORTED,
            "",
            "Эти PR не размечены метками release:*: проверьте и расставьте метки.",
            "",
        ]
        lines += [
            "- [%s](%s) (#%s)" % (clean_title(pull.get("title")), pull.get("html_url"), pull.get("number"))
            for pull in grouped[GROUP_UNSORTED]
        ]
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def parse_headers(text):
    headers = {}
    for line in text.splitlines():
        if ":" in line:
            name, value = line.split(":", 1)
            headers[name.strip().lower()] = value.strip()
    return headers


def rate_limit_message(headers):
    retry_after = headers.get("retry-after")
    if retry_after:
        return "лимит GitHub: повтор через %s с" % retry_after
    reset = headers.get("x-ratelimit-reset")
    if reset:
        try:
            moment = datetime.datetime.fromtimestamp(int(reset), tz=datetime.timezone.utc)
            return "лимит GitHub: сбросится %s UTC" % moment.strftime("%Y-%m-%d %H:%M")
        except (ValueError, OverflowError):
            pass
    return "лимит GitHub исчерпан"


def run_gh(args):
    """gh api с разбором статуса и заголовков. Только 200 даёт тело."""
    try:
        completed = subprocess.run(
            ["gh", "api", "-i"] + args,
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        raise ApiError("gh не найден в PATH")
    raw = completed.stdout.replace("\r\n", "\n")
    head, _, body = raw.partition("\n\n")
    status_line = head.splitlines()[0] if head else ""
    match = re.search(r"\b(\d{3})\b", status_line)
    status = int(match.group(1)) if match else 0
    headers = parse_headers("\n".join(head.splitlines()[1:]))

    if status in (403, 429):
        raise ApiError(rate_limit_message(headers), status=status)
    if status != 200:
        if status == 0:
            raise ApiError("gh api завершился с кодом %s: %s" % (completed.returncode, completed.stderr.strip()))
        raise ApiError("GitHub API ответил %s" % status, status=status)
    return body.strip()


def pulls_for_commit(repo, sha, cache_dir):
    if cache_dir:
        path = os.path.join(cache_dir, "%s.json" % sha)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as handle:
                return json.load(handle)
    body = run_gh(["repos/%s/commits/%s/pulls" % (repo, sha)])
    pulls = json.loads(body)
    if not isinstance(pulls, list):
        raise ApiError("неожиданный ответ для %s: ожидался список PR" % sha)
    if cache_dir:
        os.makedirs(cache_dir, exist_ok=True)
        with open(os.path.join(cache_dir, "%s.json" % sha), "w", encoding="utf-8") as handle:
            json.dump(pulls, handle, ensure_ascii=False, indent=2)
    return pulls


def git_lines(args):
    completed = subprocess.run(["git"] + args, capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or "git завершился с кодом %s" % completed.returncode)
    return [line.strip() for line in completed.stdout.splitlines() if line.strip()]


def previous_tag():
    try:
        tags = git_lines(["describe", "--tags", "--abbrev=0", "--match", "v*"])
    except RuntimeError:
        return ""
    return tags[0] if tags else ""


def repo_slug():
    completed = subprocess.run(
        ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0 or not completed.stdout.strip():
        raise RuntimeError("не удалось определить репозиторий; задайте --repo")
    return completed.stdout.strip()


def insert_section(text, notes, tag):
    """Вставляет раздел в CHANGELOG, заменяя прежний раздел того же тега."""
    lines = text.splitlines()
    header = "## [%s]" % tag.lstrip("v")
    section = notes.rstrip().splitlines()
    for index, line in enumerate(lines):
        if not line.startswith("## ["):
            continue
        if line.startswith(header):
            end = index + 1
            while end < len(lines) and not lines[end].startswith("## ["):
                end += 1
            return "\n".join(lines[:index] + section + lines[end:]) + "\n"
        return "\n".join(lines[:index] + [""] + section + lines[index:]) + "\n"
    return "\n".join(lines + [""] + section) + "\n"


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Заметки о релизе из заголовков PR")
    parser.add_argument("--tag", help="тег релиза, например v1.1.0")
    parser.add_argument("--from", dest="from_ref", help="начало диапазона: тег или SHA")
    parser.add_argument("--to", dest="to_ref", default="HEAD", help="конец диапазона (по умолчанию HEAD)")
    parser.add_argument("--repo", help="owner/repo; по умолчанию из gh")
    parser.add_argument("--dry-run", action="store_true", help="печатать markdown, не писать файлы")
    parser.add_argument("--write", action="store_true", help="записать docs/releases/<tag>.md и CHANGELOG")
    parser.add_argument("--cache-dir", help="каталог кэша ответов API")
    parser.add_argument("--date", help="дата раздела, по умолчанию сегодня")
    args = parser.parse_args(argv)
    if args.dry_run == args.write:
        parser.error("нужен ровно один режим: --dry-run или --write")
    if args.write and not args.tag:
        parser.error("--write требует --tag")
    return args


def collect_pulls(repo, commits, cache_dir):
    collected = {}
    for sha in commits:
        for pull in pulls_for_commit(repo, sha, cache_dir):
            number = pull.get("number")
            if number is not None:
                collected.setdefault(number, pull)
    return select_pulls(list(collected.values()))


def main(argv=None):
    args = parse_args(argv)
    from_ref = args.from_ref or previous_tag()
    if not from_ref:
        print("ОШИБКА: нет предыдущего тега и не задан --from", file=sys.stderr)
        return 2
    try:
        repo = args.repo or repo_slug()
        commits = git_lines(["rev-list", "--first-parent", "%s..%s" % (from_ref, args.to_ref)])
    except RuntimeError as error:
        print("ОШИБКА: %s" % error, file=sys.stderr)
        return 2
    if not commits:
        print("ОШИБКА: пустой диапазон %s..%s: проверьте тег" % (from_ref, args.to_ref), file=sys.stderr)
        return 2

    try:
        pulls = collect_pulls(repo, commits, args.cache_dir)
    except ApiError as error:
        print("ОШИБКА: %s" % error, file=sys.stderr)
        return 4
    except json.JSONDecodeError as error:
        print("ОШИБКА: не разобран ответ API: %s" % error, file=sys.stderr)
        return 4

    tag = args.tag or "следующий выпуск"
    notes = render(pulls, tag, args.date or datetime.date.today().isoformat())
    if notes is None:
        print("ОШИБКА: в диапазоне нет PR для заметок: проверьте диапазон и метки", file=sys.stderr)
        return 3

    if args.dry_run:
        sys.stdout.write(notes)
        return 0

    os.makedirs("docs/releases", exist_ok=True)
    release_path = os.path.join("docs/releases", "%s.md" % tag)
    with open(release_path, "w", encoding="utf-8") as handle:
        handle.write(notes)
    changelog = "CHANGELOG.md"
    if os.path.exists(changelog):
        with open(changelog, encoding="utf-8") as handle:
            text = handle.read()
        text = insert_section(text, notes, tag)
    else:
        text = "# История изменений\n\n" + notes
    with open(changelog, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("записано: %s и %s" % (release_path, changelog))
    return 0


if __name__ == "__main__":
    sys.exit(main())
