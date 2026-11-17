#!/usr/bin/env python3
"""Оценка правил репозитория GitHub для создания тега.

Правила приходят отдельными запросами, поэтому скрипт читает NDJSON:
по одному объекту ruleset на строку. Такой вход удобно подменять фикстурой.

Коды возврата:
  0 — создавать тег можно;
  3 — тег создавать нельзя;
  2 — входные данные не разобраны (выпуск останавливается).
"""

import argparse
import fnmatch
import json
import sys

BLOCKING_RULES = frozenset({"creation", "update", "deletion"})


def ruleset_applies(ruleset, ref):
    """Правило действует на этот ref согласно условиям include/exclude."""
    conditions = (ruleset.get("conditions") or {}).get("ref_name") or {}
    include = conditions.get("include") or []
    exclude = conditions.get("exclude") or []
    if include and not any(fnmatch.fnmatch(ref, pattern) for pattern in include):
        return False
    return not any(fnmatch.fnmatch(ref, pattern) for pattern in exclude)


def ruleset_blocks(ruleset, ref, uid):
    """True, если правила запрещают создавать тег этой учётной записи."""
    if ruleset.get("enforcement") != "active":
        return False
    if not ruleset_applies(ruleset, ref):
        return False
    if not any(rule.get("type") in BLOCKING_RULES for rule in ruleset.get("rules", [])):
        return False
    bypass = ruleset.get("bypass_actors") or []
    if any(item.get("actor_type") == "User" and item.get("actor_id") == uid for item in bypass):
        return False
    # Исключение другого типа (роль, команда, организация) скрипт подтвердить
    # не может: считаем правило действующим, чтобы не выпустить вслепую.
    return True


def blocking_ruleset(rulesets, ref, uid):
    """Имя первого правила, которое мешает созданию тега, или None."""
    for ruleset in rulesets:
        if ruleset.get("target") != "tag":
            continue
        if ruleset_blocks(ruleset, ref, uid):
            return ruleset.get("name") or str(ruleset.get("id"))
    return None


def load_rulesets(stream):
    rulesets = []
    for line in stream:
        line = line.strip()
        if not line:
            continue
        rulesets.append(json.loads(line))
    return rulesets


def main(argv=None):
    parser = argparse.ArgumentParser(description="Проверка правил GitHub для создания тега")
    parser.add_argument("--ref", required=True, help="например, refs/tags/v1.0.0")
    parser.add_argument("--uid", required=True, type=int, help="числовой id пользователя GitHub")
    args = parser.parse_args(argv)

    try:
        rulesets = load_rulesets(sys.stdin)
    except json.JSONDecodeError as error:
        print("не разобран ответ API правил: %s" % error, file=sys.stderr)
        return 2

    name = blocking_ruleset(rulesets, args.ref, args.uid)
    if name is None:
        print("правила не мешают созданию %s" % args.ref)
        return 0
    print("правило «%s» запрещает создание %s для учётной записи %s" % (name, args.ref, args.uid))
    return 3


if __name__ == "__main__":
    sys.exit(main())
