"""Independent, offline bounded export comparison and stack evidence validation.

No database, network, import, cursor, or source-file write capabilities.
"""
import argparse
import hashlib
import json
import re
import sys
import tarfile
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

MAX_RECORDS = 500
MAX_BYTES = 16 * 1024 * 1024
COMPONENTS = {"teslamate", "teslamateapi", "grafana", "postgres", "bridge", "adapter"}
CHECKS = {"schema", "null_units_time", "pagination", "bounded_reconciliation", "restore"}


class Invalid(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def load(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, "input exceeds 16 MiB bound")
    return json.loads(raw, object_pairs_hook=unique_pairs, parse_float=Decimal,
                      parse_constant=lambda _: (_ for _ in ()).throw(Invalid("non-finite JSON number")))


def utc(value):
    require(isinstance(value, str), "UTC timestamp required")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise Invalid("invalid UTC timestamp") from error
    require(result.utcoffset() == timedelta(0), "explicit UTC timestamp required")
    return result


def canonical(value):
    # Preserve missing vs null, numeric zero vs false, arrays, units and unknown fields.
    if isinstance(value, dict):
        return ("object", tuple((key, canonical(item)) for key, item in sorted(value.items())))
    if isinstance(value, list):
        return ("array", tuple(canonical(item) for item in value))
    return (type(value).__name__, str(value))


def source_bindings():
    root = Path(__file__).resolve().parents[1]
    result = {}
    for name, directory in (("adapter", "adapter"), ("bridge", "archive-bridge")):
        digest = hashlib.sha256()
        base = root / directory
        files = sorted(p for p in base.rglob("*") if p.is_file()
                       and (p.suffix == ".go" or p.name in {"go.mod", "go.sum", "Dockerfile"}))
        for path in files:
            # These are UTF-8 source text files. Git's CRLF checkout conversion
            # must not change the source identity between Windows and Linux.
            content = path.read_bytes().replace(b"\r\n", b"\n")
            digest.update(path.relative_to(base).as_posix().encode() + b"\0" + content + b"\0")
        result[name] = digest.hexdigest()
    return result


def local_image_artifact(component, manifest_path):
    image_id = component.get("image")
    require(isinstance(image_id,str) and re.fullmatch(r"sha256:[0-9a-f]{64}",image_id), "immutable local image ID required")
    artifact = component.get("artifact", {})
    path = Path(manifest_path).resolve().parent / artifact.get("file", "")
    require(path.is_file() and path.stat().st_size <= 512*1024*1024, "local image archive missing or oversized")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""): digest.update(chunk)
    require(digest.hexdigest() == artifact.get("sha256"), "local image archive checksum mismatch")
    with tarfile.open(path, "r:*") as archive:
        members = archive.getmembers()
        require(len(members) == len({m.name for m in members}), "duplicate archive members")
        def metadata(name):
            member = archive.getmember(name)
            require(member.isfile() and member.size <= 1024*1024, "invalid OCI metadata member")
            return json.loads(archive.extractfile(member).read(), object_pairs_hook=unique_pairs)
        index = metadata("index.json")
        index_types={"application/vnd.oci.image.index.v1+json","application/vnd.docker.distribution.manifest.list.v2+json"}
        manifest_types={"application/vnd.oci.image.manifest.v1+json","application/vnd.docker.distribution.manifest.v2+json"}
        config_types={"application/vnd.oci.image.config.v1+json","application/vnd.docker.container.image.v1+json"}
        require(type(index.get("schemaVersion")) is int and index["schemaVersion"]==2 and index.get("mediaType") in index_types,"invalid OCI root index")
        require(isinstance(index.get("manifests"),list) and index["manifests"],"OCI root index has no manifests")
        roots=[descriptor for descriptor in index["manifests"] if isinstance(descriptor,dict) and descriptor.get("digest")==image_id]
        require(len(roots)==1, "local image archive ID mismatch")
        platforms = set()
        visited = {}
        def verify_blob(descriptor, role):
            require(isinstance(descriptor,dict),"invalid OCI descriptor")
            expected=descriptor.get("digest")
            require(isinstance(expected,str) and re.fullmatch(r"sha256:[0-9a-f]{64}",expected), "invalid OCI digest")
            media_type=descriptor.get("mediaType")
            require(isinstance(media_type,str) and media_type,"OCI descriptor media type required")
            if role=="image":require(media_type in index_types|manifest_types,"OCI image descriptor must reference an index or manifest")
            if role=="config":require(media_type in config_types,"OCI config descriptor media type required")
            if role=="attestation":require(media_type in manifest_types,"OCI attestation manifest required")
            if role=="empty-config":require(media_type=="application/vnd.oci.empty.v1+json","OCI empty config required")
            cache_key=(expected,role)
            if cache_key in visited:return visited[cache_key]
            name="blobs/sha256/"+expected[7:]
            member=archive.getmember(name)
            require(member.isfile(), "OCI digest is not a regular file")
            require(type(descriptor.get("size")) is int and descriptor["size"]==member.size,"OCI descriptor size mismatch")
            actual=hashlib.sha256()
            with archive.extractfile(member) as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b""):actual.update(chunk)
            require(actual.hexdigest()==expected[7:],"OCI blob digest mismatch")
            result=None
            if role!="layer":
                body=metadata(name)
                require(isinstance(body,dict),"OCI metadata must be an object")
                if role=="empty-config":
                    require(body=={},"OCI attestation empty config must be empty")
                elif role=="config":
                    require(isinstance(body.get("os"),str) and isinstance(body.get("architecture"),str),"OCI config platform missing")
                    rootfs=body.get("rootfs",{})
                    require(isinstance(rootfs,dict) and rootfs.get("type")=="layers" and isinstance(rootfs.get("diff_ids"),list),"OCI config rootfs missing")
                    require(all(isinstance(d,str) and re.fullmatch(r"sha256:[0-9a-f]{64}",d) for d in rootfs["diff_ids"]),"invalid OCI rootfs digest")
                    platforms.add(body["os"]+"/"+body["architecture"])
                    result=len(rootfs["diff_ids"])
                else:
                    require(type(body.get("schemaVersion")) is int and body["schemaVersion"]==2 and body.get("mediaType")==media_type,"invalid OCI image schema/media type")
                    if role=="attestation":
                        require(body.get("artifactType")=="application/vnd.docker.attestation.manifest.v1+json" and isinstance(body.get("config"),dict) and isinstance(body.get("layers"),list),"invalid Docker attestation artifact")
                        require(isinstance(body.get("subject"),dict) and descriptor.get("annotations",{}).get("vnd.docker.reference.digest")==body["subject"].get("digest"),"attestation subject mismatch")
                        verify_blob(body["config"],"empty-config")
                        verify_blob(body["subject"],"image")
                        for child in body["layers"]:verify_blob(child,"layer")
                    elif media_type in index_types:
                        require(isinstance(body.get("manifests"),list) and body["manifests"],"OCI image index has no manifests")
                        for child in body["manifests"]:
                            child_role="attestation" if isinstance(child,dict) and child.get("annotations",{}).get("vnd.docker.reference.type")=="attestation-manifest" else "image"
                            verify_blob(child,child_role)
                    else:
                        require("artifactType" not in body,"OCI image cannot be an attestation artifact")
                        require(isinstance(body.get("config"),dict) and isinstance(body.get("layers"),list),"OCI image manifest requires config and layers")
                        count=verify_blob(body["config"],"config")
                        require(count==len(body["layers"]),"OCI config/manifest layer count mismatch")
                        for child in body["layers"]:verify_blob(child,"layer")
            visited[cache_key]=result
            return result
        verify_blob(roots[0],"image")
        require(component.get("platform") in platforms,"local image platform mismatch")
    return artifact["sha256"]


def export_rows(export, identity, interval):
    require(isinstance(export, dict) and type(export.get("schema_version")) is int and export["schema_version"] == 1, "unsupported export schema")
    require(export.get("identity") == identity, "source/account/car identity mismatch")
    require(export.get("interval") == interval, "UTC interval mismatch")
    start, end = utc(interval["from"]), utc(interval["to"])
    require(start < end, "empty or reversed interval")
    require(export.get("selection") == "started_at_[from,to)", "explicit selection rule required")
    result = {}
    for kind in ("drives", "charges"):
        rows = export.get(kind)
        require(isinstance(rows, list) and len(rows) <= MAX_RECORDS, kind + " exceeds bounded record limit or is missing")
        coverage = export.get("coverage", {}).get(kind, {})
        require(coverage.get("complete") is True and coverage.get("truncated") is False,
                kind + " partial/truncated coverage")
        require(type(coverage.get("total")) is int and coverage["total"] == len(rows), kind + " count mismatch")
        require(coverage.get("next_page") is None and "next_page" in coverage, kind + " pagination incomplete")
        indexed = {}
        for row in rows:
            require(isinstance(row, dict), "record must be an object")
            require(row.get("identity") == identity, "record source/account/car identity mismatch")
            for field, value in identity.items():
                require(field not in row or row[field] == value, "record source/account/car identity mismatch")
            require("car_id" not in row, "raw car_id cannot substitute for stable car_uid; normalize the scoped export first")
            record_id = row.get("source_record_id")
            require(isinstance(record_id, str) and record_id.strip(), "stable source record ID required")
            require(record_id not in indexed, "duplicate source record ID")
            record_start = utc(row.get("started_at"))
            require(start <= record_start < end, "record outside requested interval")
            if row.get("ended_at") is not None:
                require(utc(row["ended_at"]) >= record_start, "record end precedes start")
            require(isinstance(row.get("units"), dict), "explicit per-record units object required")
            require(all(isinstance(v, str) or v is None for v in row["units"].values()), "unit values must be strings or null")
            indexed[record_id] = row
        result[kind] = indexed
    return result


def compare(args):
    identity = {key: getattr(args, key) for key in ("source_instance_id", "account_uid", "car_uid")}
    require(all(isinstance(v, str) and v.strip() for v in identity.values()), "nonempty explicit identity required")
    interval = {"from": args.start, "to": args.end}
    before = export_rows(load(args.before), identity, interval)
    after = export_rows(load(args.after), identity, interval)
    changes = {}
    for kind in ("drives", "charges"):
        old, new = before[kind], after[kind]
        # Reports expose field names, never account IDs, addresses, coordinates or raw values.
        changes[kind] = {
            "before_count": len(old), "after_count": len(new),
            "added_count": len(new.keys() - old.keys()),
            "removed_count": len(old.keys() - new.keys()),
            "old_id_corrections": [
                {"record_id_sha256": hashlib.sha256(key.encode()).hexdigest(),
                 "changed_fields": sorted(field for field in old[key].keys() | new[key].keys()
                                          if field not in old[key] or field not in new[key]
                                          or canonical(old[key][field]) != canonical(new[key][field]))}
                for key in sorted(old.keys() & new.keys()) if canonical(old[key]) != canonical(new[key])
            ],
        }
    changed = any(v["added_count"] or v["removed_count"] or v["old_id_corrections"] for v in changes.values())
    return {"status": "CHANGES_FOUND" if changed else "PASS_BOUNDED", "scope": "EXPLICIT_INTERVAL_ONLY",
            "whole_history_verified": False, "source_files_modified": False, "changes": changes}, int(changed)


def validate_manifest(args):
    manifest = load(args.manifest)
    require(type(manifest.get("schema_version")) is int and manifest["schema_version"] == 1, "unsupported manifest schema")
    components = manifest.get("components", {})
    require(set(components) == COMPONENTS, "all six explicit components required (including local adapter and archive bridge)")
    images = {}
    for name, component in components.items():
        require(isinstance(component, dict), name + " definition missing")
        version = component.get("version")
        version_pattern = r"\d+\.\d+(?:\.\d+)?(?:[-+][\w.-]+)?" if name == "postgres" else r"v?\d+\.\d+\.\d+(?:[-+][\w.-]+)?"
        require(isinstance(version, str) and re.fullmatch(version_pattern, version), name + " explicit release version required")
        image = component.get("image")
        if component.get("kind") == "local-image":
            require(name in {"adapter","bridge"}, "only local adapter/bridge artifacts permitted")
            artifact_sha = local_image_artifact(component,args.manifest)
        else:
            require(component.get("kind", "registry-image") == "registry-image", "unsupported image kind")
            require(isinstance(image, str) and re.fullmatch(r"[\w./:-]+@sha256:[0-9a-f]{64}", image), name + " pinned image digest required")
            require(":latest@" not in image.lower(), name + " latest rejected")
        require(component.get("platform") in {"linux/amd64", "linux/arm64"}, name + " explicit platform required")
        images[name] = {"version": version, "image": image, "platform": component["platform"]}
        if component.get("kind") == "local-image":
            images[name].update(kind="local-image",artifact_sha256=artifact_sha)
    require(components["teslamate"]["version"].lstrip("v") == "4.3.0", "TeslaMate 4.3.0 target required")
    require(len({item["platform"] for item in images.values()}) == 1, "mixed component platforms rejected")
    require(manifest.get("status") == "VERIFIED_OFFLINE", "component combination is NOT_QUALIFIED")
    evidence_ref = manifest.get("evidence", {})
    evidence_path = Path(args.manifest).resolve().parent / evidence_ref.get("file", "")
    with evidence_path.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, "evidence exceeds 16 MiB bound")
    require(hashlib.sha256(raw).hexdigest() == evidence_ref.get("sha256"), "evidence checksum mismatch")
    evidence = load(evidence_path)
    require(evidence.get("scope") == "ISOLATED_EXACT_STACK", "exact isolated stack evidence required")
    require(evidence.get("observed_components") == images, "unverified component combination")
    require(evidence.get("source_bindings") == source_bindings(), "local source binding mismatch")
    checks = evidence.get("checks", {})
    require(CHECKS <= checks.keys() and all(checks[c] == "PASS" for c in CHECKS), "required stack checks missing or failed")
    artifacts=evidence.get("artifacts",{})
    for check in CHECKS:
        reference=artifacts.get(check,{})
        artifact_path=evidence_path.resolve().parent/reference.get("file","")
        require(artifact_path.is_file() and artifact_path.stat().st_size<=MAX_BYTES,"check artifact missing or oversized")
        require(hashlib.sha256(artifact_path.read_bytes()).hexdigest()==reference.get("sha256"),"check artifact checksum mismatch")
    require(isinstance(evidence.get("run_id"), str) and evidence["run_id"].strip(), "evidence run required")
    utc(evidence.get("observed_at"))
    return {"status": "QUALIFIED_LOCAL_ONLY" if any(c.get("kind")=="local-image" for c in components.values()) else "QUALIFIED_OFFLINE_EVIDENCE", "production_verified": False,
            "deployment_authorized": False, "evidence_sha256": evidence_ref["sha256"]}, 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    diff = commands.add_parser("compare")
    for flag in ("before", "after", "source-instance-id", "account-uid", "car-uid"):
        diff.add_argument("--" + flag, required=True)
    diff.add_argument("--from", dest="start", required=True)
    diff.add_argument("--to", dest="end", required=True)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("manifest")
    args = parser.parse_args()
    try:
        report, code = compare(args) if args.command == "compare" else validate_manifest(args)
    except (Invalid, ValueError, OSError, TypeError, KeyError, AttributeError, RecursionError, tarfile.TarError) as error:
        # Validation errors use fixed explanations; do not print file contents or OS paths.
        message = str(error) if isinstance(error, Invalid) else "invalid or unreadable input"
        report, code = {"status": "NOT_QUALIFIED", "reason": message}, 2
    print(json.dumps(report, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
