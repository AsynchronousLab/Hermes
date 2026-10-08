import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_ci_gradle", TOOLS / "prepare_ci_gradle.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class GradleDistributionTest(unittest.TestCase):
    def rewrite(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle-wrapper.properties"
            path.write_text(text)
            prepare.use_canonical_distribution(path)
            return path.read_text()

    def test_committed_escaped_url_is_replaced_and_version_is_preserved(self):
        original = (TOOLS.parent.parent / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        rewritten = self.rewrite(original)
        self.assertIn(r"distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip", rewritten)
        self.assertNotIn(r"distributionUrl=https\://mirrors.cloud.tencent.com", rewritten)
        self.assertIn("networkTimeout=60000", rewritten)

    def test_unescaped_url_and_all_distribution(self):
        rewritten = self.rewrite("distributionUrl=https://mirrors.cloud.tencent.com/gradle/gradle-8.14-all.zip\n")
        self.assertEqual("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-all.zip\n", rewritten)

    def test_canonical_url_is_idempotent(self):
        original = "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n"
        self.assertEqual(original, self.rewrite(original))

    def test_missing_duplicate_or_unexpected_url_fails_closed(self):
        for text in ("", "distributionUrl=https://example.invalid/gradle.zip\n", "distributionUrl=x\ndistributionUrl=y\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.rewrite(text)


class ReleaseSourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-q")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-q", "--allow-empty", "-m", "release")
        self.git("tag", "v1")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "tag", "-a", "v1-annotated", "-m", "release")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, text=True)

    def verify(self, tag):
        return subprocess.run(["bash", str(TOOLS / "verify_release_source.sh"), tag], cwd=self.repo, capture_output=True, text=True)

    def test_matching_lightweight_and_annotated_tags(self):
        for tag in ("v1", "v1-annotated"):
            with self.subTest(tag=tag):
                self.assertEqual(0, self.verify(tag).returncode)

    def test_branch_head_cannot_overwrite_an_older_release(self):
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-q", "--allow-empty", "-m", "later work")
        result = self.verify("v1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Refusing to publish", result.stderr)

    def test_missing_tag_is_rejected(self):
        self.assertNotEqual(0, self.verify("v-missing").returncode)


if __name__ == "__main__":
    unittest.main()
