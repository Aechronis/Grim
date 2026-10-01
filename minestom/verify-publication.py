#!/usr/bin/env python3
"""Check the actual Maven publication before uploading it to Central."""

import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("--signed", action="store_true")
    args = parser.parse_args()
    assert re.fullmatch(r"\d+\.\d+\.\d+(?:[.-][A-Za-z0-9]+)*", args.version), "Invalid release version"
    assert "SNAPSHOT" not in args.version, "Central release must not be a snapshot"
    folder = args.repository / "net/aechronis/grim-minestom" / args.version
    stem = f"grim-minestom-{args.version}"
    artifacts = [folder / f"{stem}{suffix}" for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")]
    for artifact in artifacts:
        assert artifact.is_file() and artifact.stat().st_size, artifact
        for algorithm in ("md5", "sha1"):
            expected = artifact.with_name(f"{artifact.name}.{algorithm}").read_text().strip()
            assert hashlib.new(algorithm, artifact.read_bytes()).hexdigest() == expected, artifact
        if args.signed:
            subprocess.run(["gpg", "--batch", "--verify", str(artifact) + ".asc", str(artifact)], check=True)

    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(folder / f"{stem}.pom").getroot()
    for name in ("name", "description", "url", "licenses/license/name", "developers/developer/name", "scm/connection"):
        element = pom.find("/".join("m:" + part for part in name.split("/")), ns)
        assert element is not None and element.text, f"Missing POM {name}"
    assert pom.findtext("m:groupId", namespaces=ns) == "net.aechronis"
    assert pom.findtext("m:artifactId", namespaces=ns) == "grim-minestom"
    assert pom.findtext("m:version", namespaces=ns) == args.version
    forbidden = {"ac.grim.grimac", "github.scarsz", "com.github.retrooper"}
    for dependency in pom.findall("m:dependencies/m:dependency", ns):
        assert dependency.findtext("m:groupId", namespaces=ns) not in forbidden
        assert "SNAPSHOT" not in dependency.findtext("m:version", namespaces=ns)

    metadata = json.loads((folder / f"{stem}.module").read_text())
    for variant in metadata["variants"]:
        for dependency in variant.get("dependencies", []):
            assert dependency["group"] not in forbidden
        for artifact in variant.get("files", []):
            data = (folder / artifact["url"]).read_bytes()
            assert hashlib.sha256(data).hexdigest() == artifact["sha256"]

    classes = (
        "ac/grim/grimac/minestom/MinestomSupport",
        "ac/grim/grimac/platform/minestom/GrimMinestom",
        "ac/grim/grimac/GrimAPI",
        "ac/grim/grimac/api/GrimAbstractAPI",
        "com/github/retrooper/packetevents/PacketEvents",
    )
    with ZipFile(folder / f"{stem}.jar") as archive:
        entries = set(archive.namelist())
        for name in classes:
            assert name + ".class" in entries, name
        for prefix in ("net/minestom/", "io/netty/", "net/kyori/", "com/google/common/"):
            assert not any(name.startswith(prefix) and name.endswith(".class") for name in entries), prefix
        assert "META-INF/LICENSE" in entries
        assert "META-INF/licenses/GrimAPI-LICENSE.txt" in entries
        assert "grimac.properties" in entries
    with ZipFile(folder / f"{stem}-sources.jar") as archive:
        for name in classes:
            assert name + ".java" in archive.namelist(), name
    with ZipFile(folder / f"{stem}-javadoc.jar") as archive:
        assert "ac/grim/grimac/platform/minestom/GrimMinestom.html" in archive.namelist()
        assert "engine/ac/grim/grimac/GrimAPI.html" in archive.namelist()
    print(f"PUBLICATION_OK net.aechronis:grim-minestom:{args.version}")


if __name__ == "__main__":
    main()
