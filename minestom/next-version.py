#!/usr/bin/env python3
"""Select the next Minestom release from Maven Central and reserved Git tags."""

import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
METADATA_URL = "https://repo.maven.apache.org/maven2/net/aechronis/grim-minestom/maven-metadata.xml"


def grim_version():
    match = re.search(
        r'^val\s+baseVersion\s*=\s*"(\d+\.\d+\.\d+)"\s*$',
        (ROOT / "build.gradle.kts").read_text(),
        re.MULTILINE,
    )
    if not match:
        raise ValueError("Cannot read Grim's baseVersion from build.gradle.kts")
    return match[1]


def published_versions():
    request = Request(METADATA_URL, headers={"Cache-Control": "no-cache"})
    try:
        with urlopen(request, timeout=30) as response:
            metadata = ET.fromstring(response.read())
    except HTTPError as error:
        if error.code == 404:
            return []
        raise
    if (metadata.tag != "metadata"
            or metadata.findtext("groupId") != "net.aechronis"
            or metadata.findtext("artifactId") != "grim-minestom"):
        raise ValueError("Unexpected Maven Central metadata for grim-minestom")
    versions = metadata.findall("versioning/versions/version")
    if not versions or any(not version.text or not version.text.strip() for version in versions):
        raise ValueError("Maven Central metadata is missing release versions")
    return [version.text.strip() for version in versions]


def reserved_versions(base):
    # Read the remote directly: checkout can predate a preceding publish run.
    result = subprocess.run(
        ["git", "ls-remote", "--tags", "--refs", "origin", f"refs/tags/{base}-minestom.*"],
        cwd=ROOT, check=True, capture_output=True, text=True, timeout=30,
    )
    return [line.split()[1].removeprefix("refs/tags/") for line in result.stdout.splitlines()]


def next_version(base, versions):
    pattern = re.compile(re.escape(base) + r"-minestom\.(\d+)")
    numbers = [int(match[1]) for version in versions if (match := pattern.fullmatch(version))]
    return f"{base}-minestom.{max(numbers, default=0) + 1}"


def main():
    base = grim_version()
    print(next_version(base, published_versions() + reserved_versions(base)))


if __name__ == "__main__":
    main()
