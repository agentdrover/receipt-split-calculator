"""Тесты сборщика заметок о релизе.

Сеть не нужна: ответы GitHub API подменяются фикстурами и фальшивым gh.
"""

import importlib.util
import json
import os
import pathlib
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "scripts" / "release-notes.py"
SPEC = importlib.util.spec_from_file_location("release_notes", SCRIPT)
NOTES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NOTES)


def pull(number, title, labels=(), state="main", merged=True, url=None, merge_sha=None):
    data = {
        "number": number,
        "title": title,
        "html_url": url or "https://github.com/o/r/pull/%d" % number,
        "labels": [{"name": name} for name in labels],
        "base": {"ref": state},
        "merged_at": "2026-11-17T10:00:00Z" if merged else None,
    }
    if merged:
        data["merge_commit_sha"] = merge_sha if merge_sha is not None else "sha1"
    return data


def fake_gh(directory, stdout, rc=0, stderr=""):
    path = pathlib.Path(directory) / "gh"
    path.write_text("#!/bin/sh\ncat <<'EOF'\n%s\nEOF\nexit %s\n" % (stdout, rc), encoding="utf-8")
    path.chmod(0o755)
    return str(pathlib.Path(directory))


class TitleTest(unittest.TestCase):
    def test_strips_conventional_prefix(self):
        self.assertEqual(NOTES.clean_title("feat(task): релизный контур"), "релизный контур")

    def test_strips_prefix_with_scope(self):
        self.assertEqual(NOTES.clean_title("fix(release): гейт правил"), "гейт правил")

    def test_keeps_plain_title(self):
        self.assertEqual(NOTES.clean_title("Просто заголовок"), "Просто заголовок")

    def test_strips_hub_task_number(self):
        self.assertEqual(NOTES.clean_title("Релизный контур (#2370)"), "Релизный контур")

    def test_keeps_reference_inside_title(self):
        self.assertEqual(NOTES.clean_title("Починить (#42) в ядре"), "Починить (#42) в ядре")

    def test_escapes_markdown_links_and_images(self):
        escaped = NOTES.escape_markdown("x](https://evil) ![p](https://evil/p.gif) [tail")
        self.assertNotIn("](", escaped)
        self.assertIn("\\]", escaped)
        self.assertIn("\\[", escaped)
        self.assertIn("\\!", escaped)


class GroupingTest(unittest.TestCase):
    def test_feature_label(self):
        self.assertEqual(NOTES.group_of(pull(1, "x", ["release:feature"])), NOTES.GROUP_FEATURE)

    def test_fix_label(self):
        self.assertEqual(NOTES.group_of(pull(1, "x", ["release:fix"])), NOTES.GROUP_FIX)

    def test_maintenance_label(self):
        self.assertEqual(NOTES.group_of(pull(1, "x", ["release:maintenance"])), NOTES.GROUP_MAINTENANCE)

    def test_feature_wins_over_fix(self):
        self.assertEqual(NOTES.group_of(pull(1, "x", ["release:fix", "release:feature"])), NOTES.GROUP_FEATURE)

    def test_fix_wins_over_maintenance(self):
        self.assertEqual(NOTES.group_of(pull(1, "x", ["release:maintenance", "release:fix"])), NOTES.GROUP_FIX)

    def test_skip_label_excludes(self):
        self.assertIsNone(NOTES.group_of(pull(1, "x", ["release:skip", "release:feature"])))

    def test_unlabelled_goes_to_review(self):
        self.assertEqual(NOTES.group_of(pull(1, "x")), NOTES.GROUP_UNSORTED)


class SelectionTest(unittest.TestCase):
    def in_range(self, sha):
        return sha == "sha1"

    def test_unmerged_excluded(self):
        self.assertEqual(NOTES.select_pulls([pull(1, "x", merged=False)], self.in_range), [])

    def test_other_base_excluded(self):
        self.assertEqual(NOTES.select_pulls([pull(1, "x", state="develop")], self.in_range), [])

    def test_duplicates_removed(self):
        result = NOTES.select_pulls([pull(2, "b"), pull(1, "a"), pull(2, "b")], self.in_range)
        self.assertEqual([item["number"] for item in result], [1, 2])

    def test_out_of_range_excluded(self):
        self.assertEqual(NOTES.select_pulls([pull(1, "x", merge_sha="later")], self.in_range), [])


class ValidationTest(unittest.TestCase):
    def test_incomplete_pull_raises(self):
        with self.assertRaises(NOTES.ApiError):
            NOTES.validate_pulls([{"number": 1, "merged_at": "x", "base": {"ref": "main"}}], "abc")

    def test_merged_without_merge_sha_raises(self):
        broken = pull(1, "x")
        broken.pop("merge_commit_sha")
        with self.assertRaises(NOTES.ApiError):
            NOTES.validate_pulls([broken], "abc")

    def test_valid_pull_passes(self):
        NOTES.validate_pulls([pull(1, "x")], "abc")


class RenderTest(unittest.TestCase):
    def test_sections_in_order(self):
        prs = [
            pull(1, "fix(task): исправление", ["release:fix"]),
            pull(2, "feat(task): возможность", ["release:feature"]),
        ]
        text = NOTES.render(prs, "v1.1.0", "2026-11-17")
        self.assertLess(text.index("### Возможности"), text.index("### Исправления"))
        self.assertIn("[возможность]", text)
        self.assertNotIn("feat(task):", text)

    def test_empty_sections_not_printed(self):
        text = NOTES.render([pull(1, "x", ["release:fix"])], "v1.1.0", "2026-11-17")
        self.assertNotIn("### Возможности", text)
        self.assertIn("### Исправления", text)

    def test_unlabelled_section_has_hint(self):
        text = NOTES.render([pull(1, "x")], "v1.1.0", "2026-11-17")
        self.assertIn("### Требует разбора", text)
        self.assertIn("release:*", text)
        self.assertNotIn("### Возможности", text)

    def test_skipped_only_returns_none(self):
        self.assertIsNone(NOTES.render([pull(1, "x", ["release:skip"])], "v1.1.0", "2026-11-17"))

    def test_empty_returns_none(self):
        self.assertIsNone(NOTES.render([], "v1.1.0", "2026-11-17"))

    def test_malicious_title_cannot_inject_link(self):
        text = NOTES.render([pull(1, "x](https://evil) [tail", ["release:fix"])], "v1.1.0", "2026-11-17")
        self.assertNotIn("](https://evil)", text)


class ChangelogTest(unittest.TestCase):
    BASE = "# История изменений\n\nВступление.\n\n## [1.0.0] — 2026-11-17\n\n### Возможности\n\n- старое\n"

    def test_inserts_before_first_section(self):
        notes = "## [1.1.0] — 2026-12-01\n\n### Исправления\n\n- новое\n"
        text = NOTES.insert_section(self.BASE, notes, "v1.1.0")
        self.assertLess(text.index("## [1.1.0]"), text.index("## [1.0.0]"))
        self.assertIn("старое", text)

    def test_replaces_same_section(self):
        notes = "## [1.0.0] — 2026-11-18\n\n### Исправления\n\n- обновлённое\n"
        text = NOTES.insert_section(self.BASE, notes, "v1.0.0")
        self.assertEqual(text.count("## [1.0.0]"), 1)
        self.assertIn("обновлённое", text)
        self.assertNotIn("старое", text)

    def test_replaces_section_not_at_top(self):
        base = (
            "# История изменений\n\n## [Unreleased]\n\n- потом\n\n"
            "## [1.1.0] — 2026-12-01\n\n### Возможности\n\n- старое\n\n"
            "## [1.0.0] — 2026-11-17\n\n- ещё старее\n"
        )
        notes = "## [1.1.0] — 2026-12-02\n\n### Исправления\n\n- новое\n"
        text = NOTES.insert_section(base, notes, "v1.1.0")
        self.assertEqual(text.count("## [1.1.0]"), 1)
        self.assertIn("новое", text)
        self.assertNotIn("старое", text)
        self.assertIn("## [Unreleased]", text)
        self.assertIn("## [1.0.0]", text)

    def test_keeps_following_section_of_other_kind(self):
        base = (
            "# История изменений\n\n## [1.0.0] — 2026-11-17\n\n- старое\n\n"
            "## Security policy\n\nПолитика.\n"
        )
        notes = "## [1.0.0] — 2026-11-18\n\n- новое\n"
        text = NOTES.insert_section(base, notes, "v1.0.0")
        self.assertIn("## Security policy", text)
        self.assertIn("Политика.", text)
        self.assertIn("новое", text)
        self.assertNotIn("старое", text)

    def test_appends_when_no_sections(self):
        text = NOTES.insert_section("# История изменений\n", "## [1.0.0]\n", "v1.0.0")
        self.assertIn("## [1.0.0]", text)


class TagTest(unittest.TestCase):
    def test_valid_tags(self):
        for tag in ("v1.2.3", "1.2.3", "v1.2.3-rc.1", "v0.0.1+build.5"):
            self.assertTrue(NOTES.valid_tag(tag), tag)

    def test_invalid_tags(self):
        for tag in ("", "../x", "/abs/path", "v1.2", "latest", "v1.2.3/../../x"):
            self.assertFalse(NOTES.valid_tag(tag), tag)

    def test_main_rejects_bad_tag(self):
        with self.assertRaises(SystemExit) as caught:
            NOTES.main(["--tag", "../../outside", "--dry-run"])
        self.assertEqual(caught.exception.code, 2)


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_path = os.environ.get("PATH", "")
        self.set_gh('HTTP/2.0 200 OK\n\n[]')

    def tearDown(self):
        os.environ["PATH"] = self.old_path
        self.tmp.cleanup()

    def set_gh(self, stdout, rc=0):
        os.environ["PATH"] = fake_gh(self.tmp.name, stdout, rc) + ":" + self.old_path

    def test_ok_returns_body(self):
        self.assertEqual(NOTES.run_gh(["x"]), "[]")

    def test_rate_limit_403_with_retry_after(self):
        self.set_gh("HTTP/2.0 403 Forbidden\nretry-after: 30\n\n{}", rc=1)
        with self.assertRaises(NOTES.ApiError) as caught:
            NOTES.run_gh(["x"])
        self.assertEqual(caught.exception.status, 403)
        self.assertIn("30", str(caught.exception))

    def test_rate_limit_429_with_reset(self):
        self.set_gh("HTTP/2.0 429 Too Many Requests\nx-ratelimit-reset: 1700000000\n\n{}", rc=1)
        with self.assertRaises(NOTES.ApiError) as caught:
            NOTES.run_gh(["x"])
        self.assertEqual(caught.exception.status, 429)
        self.assertIn("UTC", str(caught.exception))

    def test_server_error_raises(self):
        self.set_gh("HTTP/2.0 500 Internal Server Error\n\n{}", rc=1)
        with self.assertRaises(NOTES.ApiError) as caught:
            NOTES.run_gh(["x"])
        self.assertEqual(caught.exception.status, 500)

    def test_nonzero_exit_with_200_raises(self):
        self.set_gh('HTTP/2.0 200 OK\n\n[{"number": 1}]', rc=1)
        with self.assertRaises(NOTES.ApiError):
            NOTES.run_gh(["x"])

    def test_cache_roundtrip(self):
        self.set_gh('HTTP/2.0 200 OK\n\n[{"number": 1, "title": "t", "html_url": "u", "base": {"ref": "main"}, "merged_at": null}]')
        cache = os.path.join(self.tmp.name, "cache")
        first = NOTES.pulls_for_commit("o/r", "abc", cache)
        self.assertEqual(len(first), 1)
        self.set_gh("HTTP/2.0 500 Oops\n\n{}", rc=1)
        second = NOTES.pulls_for_commit("o/r", "abc", cache)
        self.assertEqual(second, first)

    def test_dry_run_cache_is_read_only(self):
        cache = os.path.join(self.tmp.name, "cache-dry")
        self.set_gh('HTTP/2.0 200 OK\n\n[]')
        NOTES.pulls_for_commit("o/r", "abc", cache, write_cache=False)
        self.assertFalse(os.path.exists(cache))

    def test_not_a_list_raises(self):
        self.set_gh('HTTP/2.0 200 OK\n\n{"message": "x"}')
        with self.assertRaises(NOTES.ApiError):
            NOTES.pulls_for_commit("o/r", "abc", None)

    def test_broken_json_raises(self):
        self.set_gh("HTTP/2.0 200 OK\n\nnot json")
        with self.assertRaises(NOTES.ApiError):
            NOTES.pulls_for_commit("o/r", "abc", None)


class MainTest(unittest.TestCase):
    def run_main(self, prs_by_sha, argv, in_range=None):
        checker = in_range or (lambda sha: True)
        with mock.patch.object(NOTES, "repo_slug", return_value="o/r"), \
                mock.patch.object(NOTES, "git_lines", return_value=["sha1", "sha2"]), \
                mock.patch.object(NOTES, "commit_in_range", side_effect=lambda sha, _f, _t: checker(sha)), \
                mock.patch.object(NOTES, "pulls_for_commit",
                                  side_effect=lambda repo, sha, cache, write_cache=True: prs_by_sha[sha]):
            return NOTES.main(argv)

    def test_dry_run_prints_and_writes_nothing(self):
        prs = {"sha1": [pull(1, "feat(task): новая вещь", ["release:feature"])], "sha2": []}
        with tempfile.TemporaryDirectory() as directory:
            cwd = os.getcwd()
            os.chdir(directory)
            try:
                rc = self.run_main(prs, ["--tag", "v1.1.0", "--from", "v1.0.0", "--dry-run"])
                self.assertEqual(rc, 0)
                self.assertEqual(os.listdir("."), [])
            finally:
                os.chdir(cwd)

    def test_dry_run_passes_write_cache_false(self):
        prs = {"sha1": [], "sha2": []}
        recorded = {}

        def fake_collect(repo, commits, cache_dir, in_range, write_cache):
            recorded["write_cache"] = write_cache
            return []

        with mock.patch.object(NOTES, "repo_slug", return_value="o/r"), \
                mock.patch.object(NOTES, "git_lines", return_value=["sha1"]), \
                mock.patch.object(NOTES, "collect_pulls", side_effect=fake_collect):
            NOTES.main(["--tag", "v1.1.0", "--from", "v1.0.0", "--dry-run", "--cache-dir", "cache"])
        self.assertFalse(recorded["write_cache"])

    def test_write_creates_files(self):
        prs = {"sha1": [pull(1, "feat(task): новая вещь", ["release:feature"])], "sha2": []}
        with tempfile.TemporaryDirectory() as directory:
            cwd = os.getcwd()
            os.chdir(directory)
            try:
                pathlib.Path("CHANGELOG.md").write_text("# История изменений\n\n## [1.0.0]\n", encoding="utf-8")
                rc = self.run_main(prs, ["--tag", "v1.1.0", "--from", "v1.0.0", "--write"])
                self.assertEqual(rc, 0)
                self.assertTrue(pathlib.Path("docs/releases/v1.1.0.md").exists())
                changelog = pathlib.Path("CHANGELOG.md").read_text(encoding="utf-8")
                self.assertIn("## [1.1.0]", changelog)
                self.assertLess(changelog.index("## [1.1.0]"), changelog.index("## [1.0.0]"))
            finally:
                os.chdir(cwd)

    def test_empty_range_returns_error(self):
        with mock.patch.object(NOTES, "repo_slug", return_value="o/r"), \
                mock.patch.object(NOTES, "git_lines", return_value=[]):
            self.assertEqual(NOTES.main(["--tag", "v1.1.0", "--from", "v1.0.0", "--dry-run"]), 2)

    def test_no_pulls_returns_three(self):
        self.assertEqual(self.run_main({"sha1": [], "sha2": []},
                                       ["--tag", "v1.1.0", "--from", "v1.0.0", "--dry-run"]), 3)

    def test_api_error_returns_four(self):
        def boom(*_args, **_kwargs):
            raise NOTES.ApiError("лимит", status=429)

        with mock.patch.object(NOTES, "repo_slug", return_value="o/r"), \
                mock.patch.object(NOTES, "git_lines", return_value=["sha1"]), \
                mock.patch.object(NOTES, "pulls_for_commit", side_effect=boom):
            self.assertEqual(NOTES.main(["--tag", "v1.1.0", "--from", "v1.0.0", "--dry-run"]), 4)


if __name__ == "__main__":
    unittest.main()
