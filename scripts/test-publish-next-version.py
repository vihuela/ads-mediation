#!/usr/bin/env python3
"""Exercise release version selection without network access or publishing."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class PublishVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/publish-next-version.sh"
        shutil.copyfile(Path(__file__).with_name(self.script.name), self.script)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable(self.bin / "git", '#!/bin/bash\nif [[ -n "$TEST_BRANCH" ]]; then printf "%s\\n" "$TEST_BRANCH"; else exit 1; fi\n')
        self.executable(self.bin / "curl", '''#!/bin/bash
while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--output" ]]; then
        cp "$TEST_METADATA" "$2"
        shift
    fi
    shift
done
printf '%s' "$TEST_HTTP_STATUS"
''')
        self.executable(self.root / "gradlew", '#!/bin/bash\nprintf "%s\\n" "$@" > "$TEST_GRADLE_ARGS"\n')
        self.metadata = self.root / "metadata.xml"
        self.gradle_args = self.root / "gradle-args"
        self.env = dict(os.environ)
        for key in ("VERSION_NAME", "GITHUB_REF_TYPE", "GITHUB_REF_NAME"):
            self.env.pop(key, None)
        self.env.update(
            PATH=str(self.bin) + os.pathsep + self.env["PATH"],
            GITHUB_PACKAGES_USERNAME="test-user",
            GITHUB_PACKAGES_TOKEN="test-token",
            TEST_BRANCH="main",
            TEST_METADATA=str(self.metadata),
            TEST_HTTP_STATUS="200",
            TEST_GRADLE_ARGS=str(self.gradle_args),
        )

    @staticmethod
    def executable(path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, versions=(), publish=False):
        self.metadata.write_text('<metadata><versioning><versions>' + ''.join(
            '<version>' + version + '</version>' for version in versions
        ) + '</versions></versioning></metadata>')
        args = ["bash", str(self.script)]
        if not publish:
            args.append("--print-next-version")
        result = subprocess.run(args, env=self.env, capture_output=True, text=True)
        if not publish:
            self.assertFalse(self.gradle_args.exists(), "Dry run must not invoke Gradle")
        return result

    def assert_next(self, versions, expected):
        result = self.run_script(versions)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Next package version: " + expected + "\n", result.stdout)

    def test_legacy_and_branch_versions_use_global_numeric_maximum(self):
        self.assert_next(["1.0.11", "main_1.0.11", "codex-fix_1.0.19", "main_1.0.9"], "main_1.0.20")

    def test_major_minor_order_and_branch_underscores(self):
        self.assert_next(["main_1.99.99", "feature_fix_2.10.4", "main_2.9.99"], "main_2.10.5")

    def test_prerelease_and_malformed_versions_are_ignored(self):
        self.assert_next(["main_9.0.0-SNAPSHOT", "main_9.0.0-rc.1", "junk", "main_1.2", "1.0.5"], "main_1.0.6")

    def test_empty_package_starts_at_one(self):
        self.env["TEST_HTTP_STATUS"] = "404"
        self.assert_next([], "main_1.0.0")

    def test_empty_metadata_starts_at_one(self):
        self.assert_next([], "main_1.0.0")

    def test_branch_is_sanitized(self):
        self.env["TEST_BRANCH"] = "codex/native+fix"
        self.assert_next(["main_1.0.11"], "codex-native-fix_1.0.12")

    def test_actions_detached_branch_fallback(self):
        self.env.update(TEST_BRANCH="", GITHUB_REF_TYPE="branch", GITHUB_REF_NAME="release/native")
        self.assert_next(["main_1.0.11"], "release-native_1.0.12")

    def test_local_branch_wins_over_actions_environment(self):
        self.env.update(GITHUB_REF_TYPE="branch", GITHUB_REF_NAME="stale")
        self.assert_next(["main_1.0.11"], "main_1.0.12")

    def test_detached_or_tag_requires_explicit_version(self):
        for ref_type in ("", "tag"):
            with self.subTest(ref_type=ref_type):
                self.env.update(TEST_BRANCH="", GITHUB_REF_TYPE=ref_type, GITHUB_REF_NAME="v1.0.11")
                result = self.run_script()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Cannot determine the current branch", result.stderr)

    def test_full_override_is_unchanged_and_allows_detached_head(self):
        self.env.update(TEST_BRANCH="", VERSION_NAME="main_3.0.0")
        self.assert_next(["main_1.0.11"], "main_3.0.0")

    def test_metadata_error_does_not_publish(self):
        self.env["TEST_HTTP_STATUS"] = "401"
        result = self.run_script(publish=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HTTP 401", result.stderr)
        self.assertFalse(self.gradle_args.exists())

    def test_calculated_version_reaches_gradle(self):
        result = self.run_script(["main_1.0.11"], publish=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = self.gradle_args.read_text().splitlines()
        self.assertIn("-PVERSION_NAME=main_1.0.12", args)
        self.assertIn("publishReleasePublicationToGitHubPackagesRepository", args)


if __name__ == "__main__":
    unittest.main()
