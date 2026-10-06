"""Build offline Android runtimes from official Python OCI and Termux packages."""
import argparse
import gzip
import hashlib
import io
import json
import os
import posixpath
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "research" / "runtime-cache"
REGISTRY = "https://registry-1.docker.io/v2/library/python"
TERMUX = "https://packages.termux.dev/apt/termux-main/"


def get(client, url, **kwargs):
    for attempt in range(4):
        try:
            response = client.get(url, **kwargs)
            response.raise_for_status()
            return response
        except httpx.HTTPError:
            if attempt == 3:
                raise
            time.sleep(2 * (attempt + 1))


def blob(client, url, expected, target):
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return target.read_bytes()
    data = get(client, url).content
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("Download digest mismatch")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return data


def deb_members(data):
    if data[:8] != b"!<arch>\n":
        raise ValueError("Not a deb archive")
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58])
        body = data[offset + 60:offset + 60 + size]
        offset += 60 + size + size % 2
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body), mode="r:*") as archive:
                for entry in archive:
                    if entry.isfile():
                        yield entry.name, archive.extractfile(entry).read()


def native_libraries(client, architecture, abi):
    packages = gzip.decompress(get(client, TERMUX + f"dists/stable/main/binary-{architecture}/Packages.gz").content).decode()
    target = ROOT / "app/src/main/jniLibs" / abi
    target.mkdir(parents=True, exist_ok=True)
    for package in ("proot", "libtalloc", "libandroid-shmem"):
        stanza = next(s for s in packages.split("\n\n") if s.startswith(f"Package: {package}\n"))
        values = dict(line.split(": ", 1) for line in stanza.splitlines() if ": " in line and not line.startswith(" "))
        data = blob(client, TERMUX + values["Filename"], values["SHA256"], CACHE / Path(values["Filename"]).name)
        for name, content in deb_members(data):
            base = posixpath.basename(name)
            # APK native library names cannot carry a version suffix. Rewrite
            # the ELF soname/needed string in place without shifting offsets.
            content = content.replace(b"libtalloc.so.2\0", b"libtalloc.so\0\0\0")
            if base == "proot":
                (target / "libproot.so").write_bytes(content)
            elif base in ("loader", "loader32"):
                (target / ("libproot-loader.so" if base == "loader" else "libproot-loader32.so")).write_bytes(content)
            elif base.endswith(".so") or ".so." in base:
                if base.startswith(("libtalloc", "libandroid-shmem")):
                    # Termux Android libraries use unversioned sonames.
                    (target / base.split(".so")[0]).with_suffix(".so").write_bytes(content)
    print(f"Native runtime ready: {abi}", flush=True)


def python_rootfs(client, architecture, abi, platform):
    token = get(client, "https://auth.docker.io/token", params={"service": "registry.docker.io", "scope": "repository:library/python:pull"}).json()["token"]
    client.headers.update({"Authorization": "Bearer " + token, "Accept": "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json"})
    index = get(client, REGISTRY + "/manifests/3.12-slim-bookworm").json()
    manifest_id = next(m["digest"] for m in index["manifests"] if m.get("platform", {}).get("architecture") == architecture and m["platform"].get("os") == "linux")
    response = get(client, REGISTRY + "/manifests/" + manifest_id)
    if hashlib.sha256(response.content).hexdigest() != manifest_id.split(":")[1]:
        raise ValueError("OCI manifest digest mismatch")
    manifest = response.json()
    nodes = {}
    for layer in manifest["layers"]:
        print(f"Fetching Python layer {abi}: {layer['size'] // 1024 // 1024} MiB", flush=True)
        digest = layer["digest"].split(":")[1]
        data = blob(client, REGISTRY + "/blobs/" + layer["digest"], digest, CACHE / digest)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as archive:
            for entry in archive:
                name = posixpath.normpath(entry.name).lstrip("/")
                if name == "." or not name or ".." in name.split("/"):
                    continue
                base = posixpath.basename(name)
                if base.startswith(".wh."):
                    old = posixpath.join(posixpath.dirname(name), base[4:])
                    for candidate in list(nodes):
                        if candidate == old or candidate.startswith(old + "/") or base == ".wh..wh..opq" and candidate.startswith(posixpath.dirname(name) + "/"):
                            del nodes[candidate]
                    continue
                if entry.isfile():
                    nodes[name] = ("file", entry.mode, archive.extractfile(entry).read())
                elif entry.isdir():
                    nodes[name] = ("dir", entry.mode, b"")
                elif entry.issym():
                    link = entry.linkname
                    if link.startswith("/"):
                        link = posixpath.relpath(link.lstrip("/"), posixpath.dirname(name) or ".")
                    nodes[name] = ("link", entry.mode, link)
                elif entry.islnk():
                    nodes[name] = nodes[posixpath.normpath(entry.linkname).lstrip("/")]
    # Cross-install wheels without executing code from either CPU architecture.
    wheels = CACHE / (abi + "-packages")
    wheels.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, "-m", "pip", "install", "--only-binary=:all:", "--platform", platform, "--implementation", "cp", "--python-version", "312", "--abi", "cp312", "--upgrade", "--target", str(wheels), "maimai-ffi==0.7.1", "httpx==0.28.1", "cryptography==46.0.3"], check=True)
    for file in wheels.rglob("*"):
        if file.is_file() and "__pycache__" not in file.parts:
            relative = file.relative_to(wheels)
            # Cross-installing on Windows creates launchers with the host
            # Python path embedded. They cannot run in the Linux rootfs.
            if relative.parts[0] == "bin" and file.suffix.lower() == ".exe":
                continue
            name = "usr/local/lib/python3.12/site-packages/" + relative.as_posix()
            nodes[name] = ("file", 0o644, file.read_bytes())
    nodes["app/engine.py"] = ("file", 0o644, (ROOT / "mobile/engine.py").read_bytes())
    metadata = {name: {"type": typ, "mode": mode, **({"target": data} if typ == "link" else {})} for name, (typ, mode, data) in nodes.items()}
    target = ROOT / "app/src/main/assets" / ("runtime-" + abi + ".zip")
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as output:
        for name, (typ, mode, data) in nodes.items():
            if typ == "file":
                output.writestr(name, data)
        output.writestr("runtime-metadata.json", json.dumps(metadata))
    client.headers.pop("Authorization", None)
    print(f"Packaged {target.name}: {target.stat().st_size // 1024 // 1024} MiB", flush=True)
    return {"abi": abi, "image_manifest": manifest_id, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arch", choices=["x86_64", "arm64-v8a", "all"], default="all")
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    results = []
    for architecture, termux_arch, abi, platform in [("amd64", "x86_64", "x86_64", "manylinux2014_x86_64"), ("arm64", "aarch64", "arm64-v8a", "manylinux2014_aarch64")]:
        if args.arch not in (abi, "all"):
            continue
        with httpx.Client(timeout=180, follow_redirects=True) as client:
            native_libraries(client, termux_arch, abi)
            results.append(python_rootfs(client, architecture, abi, platform))
    provenance = ROOT / "artifacts/runtime-provenance.json"
    existing = json.loads(provenance.read_text()) if provenance.exists() else []
    by_abi = {entry["abi"]: entry for entry in existing + results}
    provenance.write_text(json.dumps(list(by_abi.values()), indent=2))


if __name__ == "__main__":
    main()
