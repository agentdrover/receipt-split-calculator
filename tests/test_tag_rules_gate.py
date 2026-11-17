"""Тесты гейта правил репозитория для создания тега.

Фикстуры описывают ответы GitHub API, сеть не нужна.
"""

import importlib.util
import json
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "scripts" / "tag_rules_gate.py"
SPEC = importlib.util.spec_from_file_location("tag_rules_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)

REF = "refs/tags/v1.0.0"
UID = 12345


def ruleset(**overrides):
    base = {
        "id": 1,
        "name": "green CI before merge",
        "target": "tag",
        "enforcement": "active",
        "rules": [{"type": "creation"}],
        "bypass_actors": [],
    }
    base.update(overrides)
    return base


class RulesetGateTest(unittest.TestCase):
    def test_no_rulesets_allows_tag(self):
        self.assertIsNone(GATE.blocking_ruleset([], REF, UID))

    def test_branch_ruleset_is_ignored(self):
        data = [ruleset(target="branch")]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_inactive_ruleset_is_ignored(self):
        data = [ruleset(enforcement="disabled")]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_creation_rule_without_bypass_blocks(self):
        data = [ruleset(bypass_actors=[])]
        self.assertEqual(GATE.blocking_ruleset(data, REF, UID), "green CI before merge")

    def test_other_rules_do_not_block(self):
        data = [ruleset(rules=[{"type": "non_fast_forward"}])]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_update_rule_does_not_block_creation(self):
        data = [ruleset(rules=[{"type": "update"}])]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_deletion_rule_does_not_block_creation(self):
        data = [ruleset(rules=[{"type": "deletion"}])]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_all_pattern_blocks_any_tag(self):
        data = [ruleset(conditions={"ref_name": {"include": ["~ALL"]}})]
        self.assertIsNotNone(GATE.blocking_ruleset(data, REF, UID))

    def test_all_pattern_with_user_bypass_allows(self):
        data = [
            ruleset(
                conditions={"ref_name": {"include": ["~ALL"]}},
                bypass_actors=[{"actor_type": "User", "actor_id": UID}],
            )
        ]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_default_branch_pattern_does_not_match_tags(self):
        data = [ruleset(conditions={"ref_name": {"include": ["~DEFAULT_BRANCH"]}})]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_user_bypass_allows_tag(self):
        data = [ruleset(bypass_actors=[{"actor_type": "User", "actor_id": UID, "bypass_mode": "always"}])]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_other_actor_bypass_blocks_unknown(self):
        data = [ruleset(bypass_actors=[{"actor_type": "Organization", "actor_id": 7}])]
        self.assertIsNotNone(GATE.blocking_ruleset(data, REF, UID))

    def test_ref_include_pattern_not_matching_allows(self):
        data = [ruleset(conditions={"ref_name": {"include": ["refs/heads/*"]}})]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_ref_include_pattern_matching_blocks(self):
        data = [ruleset(conditions={"ref_name": {"include": ["refs/tags/*"]}})]
        self.assertIsNotNone(GATE.blocking_ruleset(data, REF, UID))

    def test_ref_exclude_pattern_allows(self):
        data = [ruleset(conditions={"ref_name": {"exclude": ["refs/tags/v1.*"]}})]
        self.assertIsNone(GATE.blocking_ruleset(data, REF, UID))

    def test_ndjson_parsing(self):
        stream = [
            json.dumps({"target": "branch", "id": 1}) + "\n",
            "\n",
            json.dumps({"target": "tag", "id": 2}) + "\n",
        ]
        self.assertEqual(len(GATE.load_rulesets(stream)), 2)

    def test_cli_blocks_with_exit_3(self):
        import io
        import sys

        stdin, sys.stdin = sys.stdin, io.StringIO(json.dumps(ruleset()) + "\n")
        try:
            self.assertEqual(GATE.main(["--ref", REF, "--uid", str(UID)]), 3)
        finally:
            sys.stdin = stdin

    def test_cli_allows_with_exit_0(self):
        import io
        import sys

        stdin, sys.stdin = sys.stdin, io.StringIO("")
        try:
            self.assertEqual(GATE.main(["--ref", REF, "--uid", str(UID)]), 0)
        finally:
            sys.stdin = stdin


if __name__ == "__main__":
    unittest.main()
