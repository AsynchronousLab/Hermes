"""Use the official Gradle distribution without changing its pinned version."""

from pathlib import Path
import re
import sys


def use_canonical_distribution(path: Path) -> None:
    lines = path.read_text().splitlines(keepends=True)
    entries = [i for i, line in enumerate(lines) if line.startswith("distributionUrl=")]
    if len(entries) != 1:
        raise ValueError("Expected exactly one distributionUrl property")
    index = entries[0]
    url = lines[index].split("=", 1)[1].strip().replace(r"\:", ":")
    url = url.replace("mirrors.cloud.tencent.com/gradle/", "services.gradle.org/distributions/")
    if not re.fullmatch(r"https://services\.gradle\.org/distributions/gradle-[\d.]+-(bin|all)\.zip", url):
        raise ValueError(f"Unexpected Gradle distribution URL: {url}")
    # Java properties escape the colon; validate the decoded URL above, then
    # write the conventional wrapper representation.
    newline = "\n" if lines[index].endswith("\n") else ""
    lines[index] = "distributionUrl=" + url.replace("https:", r"https\:", 1) + newline
    path.write_text("".join(lines))


if __name__ == "__main__":
    use_canonical_distribution(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("gradle/wrapper/gradle-wrapper.properties"))
