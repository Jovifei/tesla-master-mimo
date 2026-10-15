"""CLI contract tests; all records, identities and stack evidence are synthetic."""
import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import tarfile
import io
import unittest
from unittest.mock import patch
from pathlib import Path
from qualify import source_bindings

TOOL = Path(__file__).with_name("qualify.py")
IDENTITY = {"source_instance_id": "synthetic-source", "account_uid": "synthetic-account", "car_uid": "synthetic-car"}
INTERVAL = {"from": "2026-10-01T00:00:00Z", "to": "2026-10-02T00:00:00Z"}


def sample():
    return {"schema_version": 1, "identity": IDENTITY, "interval": INTERVAL,
            "selection": "started_at_[from,to)",
            "coverage": {k: {"complete": True, "truncated": False, "total": 1, "next_page": None}
                         for k in ("drives", "charges")},
            "drives": [{"source_record_id": "teslamate:drive:1", "identity": IDENTITY, "started_at": "2026-10-01T12:00:00Z",
                        "units": {"length": "km", "energy": "kWh"}, "energy": None, "unknown_field": -1}],
            "charges": [{"source_record_id": "teslamate:charging_process:1", "identity": IDENTITY, "started_at": "2026-10-01T13:00:00+00:00",
                         "units": {"energy": "kWh", "cost": None}, "energy": 0, "cost": -1}]}


class CommandTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def run_cli(self, *arguments):
        result = subprocess.run([sys.executable, str(TOOL), *map(str, arguments)], capture_output=True, text=True)
        self.assertFalse(result.stderr, result.stderr)
        return result.returncode, json.loads(result.stdout)

    def compare(self, before, after):
        old = self.write("before.json", before)
        new = self.write("after.json", after)
        hashes = [hashlib.sha256(p.read_bytes()).hexdigest() for p in (old, new)]
        result = self.run_cli("compare", "--before", old, "--after", new,
                              "--source-instance-id", IDENTITY["source_instance_id"],
                              "--account-uid", IDENTITY["account_uid"], "--car-uid", IDENTITY["car_uid"],
                              "--from", INTERVAL["from"], "--to", INTERVAL["to"])
        self.assertEqual(hashes, [hashlib.sha256(p.read_bytes()).hexdigest() for p in (old, new)])
        return result

    def test_equal_bounded_export_is_read_only(self):
        code, report = self.compare(sample(), sample())
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "PASS_BOUNDED")
        self.assertFalse(report["whole_history_verified"])

    def test_source_binding_normalizes_only_crlf_and_detects_semantic_changes(self):
        qualification=self.root/"qualification";qualification.mkdir()
        for component in ["adapter","archive-bridge"]:
            directory=self.root/component;directory.mkdir();(directory/"fixture.go").write_bytes(b"package main\r\n// fixture\r\n")
        with patch("qualify.__file__",str(qualification/"qualify.py")):
            windows=source_bindings()
            for component in ["adapter","archive-bridge"]:
                (self.root/component/"fixture.go").write_bytes(b"package main\n// fixture\n")
            self.assertEqual(windows,source_bindings())
            (self.root/"adapter"/"fixture.go").write_bytes(b"package main\n// changed behavior\n")
            self.assertNotEqual(windows["adapter"],source_bindings()["adapter"])

    def test_exact_500_bound_is_accepted_with_unique_ids(self):
        export = sample()
        export["drives"] = [dict(export["drives"][0], source_record_id="teslamate:drive:" + str(i)) for i in range(500)]
        export["coverage"]["drives"]["total"] = 500
        self.assertEqual(self.compare(export, export)[0], 0)

    def test_row_scope_bad_end_time_and_invalid_range_are_rejected(self):
        for field, value in [("identity", dict(IDENTITY, car_uid="foreign-car")), ("car_uid", "foreign-car"),
                             ("car_id", 42), ("ended_at", "bad"), ("ended_at", "2026-10-01T01:00:00Z")]:
            after = sample()
            after["drives"][0][field] = value
            self.assertEqual(self.compare(sample(), after)[0], 2)
        before, after = copy.deepcopy(sample()), copy.deepcopy(sample())
        before["interval"]["from"], before["interval"]["to"] = before["interval"]["to"], before["interval"]["from"]
        self.assertEqual(self.compare(before, after)[0], 2)

    def test_unsupported_unit_and_unknown_fields_remain_opaque(self):
        export = sample()
        export["drives"][0]["units"] = {"energy": "unrecognized-unit", "distance": None}
        export["drives"][0]["unknown_payload"] = {"flag": False, "number": -4, "missing": None}
        self.assertEqual(self.compare(export, export)[0], 0)

    def test_old_ids_detect_null_zero_negative_units_unknown_and_missing(self):
        for field, value in [("energy", 0), ("unknown_field", None), ("unknown_field", False),
                             ("units", {"length": "mi", "energy": "kWh"})]:
            with self.subTest(field=field, value=value):
                after = sample()
                after["drives"][0][field] = value
                code, report = self.compare(sample(), after)
                self.assertEqual(code, 1)
                self.assertEqual(report["changes"]["drives"]["old_id_corrections"][0]["changed_fields"], [field])
        after = sample()
        del after["drives"][0]["energy"]
        self.assertEqual(self.compare(sample(), after)[0], 1)

    def test_each_identity_and_interval_must_match(self):
        for key in IDENTITY:
            after = copy.deepcopy(sample())
            after["identity"][key] = "other"
            self.assertEqual(self.compare(sample(), after)[0], 2)
        after = copy.deepcopy(sample())
        after["interval"]["to"] = "2026-10-03T00:00:00Z"
        self.assertEqual(self.compare(sample(), after)[0], 2)

    def test_partial_truncated_count_and_next_page_are_rejected(self):
        for key, value in [("complete", False), ("truncated", True), ("total", 2), ("total", True), ("next_page", 2)]:
            after = sample()
            after["coverage"]["drives"][key] = value
            self.assertEqual(self.compare(sample(), after)[0], 2)

    def test_duplicate_ids_outside_interval_naive_time_and_overflow(self):
        for time in ["2026-10-02T00:00:00Z", "2026-10-01T12:00:00", "2026-10-01T12:00:00+08:00"]:
            after = sample()
            after["drives"][0]["started_at"] = time
            self.assertEqual(self.compare(sample(), after)[0], 2)
        for count in [2, 501]:
            after = sample()
            after["drives"] *= count
            after["coverage"]["drives"]["total"] = count
            self.assertEqual(self.compare(sample(), after)[0], 2)

    def test_added_removed_count_and_no_private_values(self):
        after = sample()
        after["drives"][0]["source_record_id"] = "teslamate:drive:2"
        after["charges"][0]["address"] = "PRIVATE-ADDRESS"
        code, report = self.compare(sample(), after)
        self.assertEqual(code, 1)
        self.assertEqual(report["changes"]["drives"]["added_count"], 1)
        self.assertEqual(report["changes"]["drives"]["removed_count"], 1)
        self.assertNotIn("PRIVATE-ADDRESS", json.dumps(report))
        self.assertNotIn(IDENTITY["account_uid"], json.dumps(report))

    def manifest(self):
        components = {name: {"version": "4.3.0" if name == "teslamate" else "1.2.3",
                             "image": "synthetic/" + name + "@sha256:" + "a" * 64,
                             "platform": "linux/amd64"}
                      for name in ("teslamate", "teslamateapi", "grafana", "postgres", "bridge", "adapter")}
        evidence = {"scope": "ISOLATED_EXACT_STACK", "observed_components": components,
                    "source_bindings": source_bindings(),
                    "run_id": "SYNTHETIC-VALIDATOR-TEST", "observed_at": "2026-10-15T00:00:00Z",
                    "checks": {check: "PASS" for check in ("schema", "null_units_time", "pagination", "bounded_reconciliation", "restore")}}
        log=self.root/"synthetic-check.log";log.write_text("SYNTHETIC VALIDATOR TEST ONLY - NO RUNTIME QUALIFICATION\n")
        evidence["artifacts"]={check:{"file":log.name,"sha256":hashlib.sha256(log.read_bytes()).hexdigest()} for check in evidence["checks"]}
        path = self.write("evidence.json", evidence)
        return {"schema_version": 1, "status": "VERIFIED_OFFLINE", "components": components,
                "evidence": {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}}

    def test_complete_synthetic_evidence_exercises_validator_only(self):
        code, report = self.run_cli("manifest", self.write("manifest.json", self.manifest()))
        self.assertEqual(code, 0)
        self.assertFalse(report["production_verified"])
        self.assertFalse(report["deployment_authorized"])

    def test_manifest_rejects_latest_missing_digest_version_and_unverified_combination(self):
        for name in ("teslamate", "teslamateapi", "grafana", "postgres", "bridge", "adapter"):
            for field, value in [("image", "synthetic/x:latest"), ("image", "synthetic/x:latest@sha256:" + "a" * 64),
                                 ("image", None), ("version", "latest"), ("platform", "linux/arm64")]:
                manifest = self.manifest()
                manifest["components"][name][field] = value
                self.assertEqual(self.run_cli("manifest", self.write("manifest.json", manifest))[0], 2)
        manifest = self.manifest()
        manifest["status"] = "NOT_QUALIFIED"
        self.assertEqual(self.run_cli("manifest", self.write("manifest.json", manifest))[0], 2)

    def test_manifest_rejects_foreign_source_binding_and_checkbox_only(self):
        manifest = self.manifest()
        evidence = json.loads((self.root / "evidence.json").read_text())
        evidence["source_bindings"]["bridge"] = "0" * 64
        path = self.write("evidence.json", evidence)
        manifest["evidence"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(self.run_cli("manifest", self.write("manifest.json", manifest))[0], 2)
        self.assertEqual(self.run_cli("manifest", self.write("manifest.json", {"qualified": True}))[0], 2)

    def test_precision_and_types_do_not_hide_corrections(self):
        before, after = sample(), sample()
        before["drives"][0]["energy"] = False
        after["drives"][0]["energy"] = 0
        self.assertEqual(self.compare(before, after)[0], 1)
        old = self.write("before.json", sample())
        new = self.write("after.json", sample())
        old.write_text(old.read_text().replace('"unknown_field": -1', '"unknown_field": 1e-10000'))
        new.write_text(new.read_text().replace('"unknown_field": -1', '"unknown_field": 0'))
        result = self.run_cli("compare", "--before", old, "--after", new,
                              "--source-instance-id", IDENTITY["source_instance_id"],
                              "--account-uid", IDENTITY["account_uid"], "--car-uid", IDENTITY["car_uid"],
                              "--from", INTERVAL["from"], "--to", INTERVAL["to"])
        self.assertEqual(result[0], 1)

    def test_manifest_rejects_evidence_tampering_failed_checks_and_missing_component(self):
        manifest = self.manifest()
        self.write("evidence.json", {})
        self.assertEqual(self.run_cli("manifest", self.write("manifest.json", manifest))[0], 2)
        manifest = self.manifest()
        del manifest["components"]["bridge"]
        self.assertEqual(self.run_cli("manifest", self.write("manifest.json", manifest))[0], 2)
        manifest = self.manifest()
        evidence = json.loads((self.root / "evidence.json").read_text())
        evidence["checks"]["pagination"] = "FAIL"
        path = self.write("evidence.json", evidence)
        manifest["evidence"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(self.run_cli("manifest", self.write("manifest.json", manifest))[0], 2)

    def test_duplicate_json_and_non_finite_are_rejected(self):
        for payload in ['{"schema_version":1,"schema_version":1}', '{"value":NaN}']:
            path = self.root / "bad.json"
            path.write_text(payload, encoding="utf-8")
            self.assertEqual(self.run_cli("manifest", path)[0], 2)

    def test_manifest_rejects_missing_or_changed_actual_check_artifacts(self):
        manifest=self.manifest()
        (self.root/"synthetic-check.log").write_text("TAMPERED")
        self.assertEqual(self.run_cli("manifest",self.write("manifest.json",manifest))[0],2)
        manifest=self.manifest()
        (self.root/"synthetic-check.log").unlink()
        self.assertEqual(self.run_cli("manifest",self.write("manifest.json",manifest))[0],2)

    def test_local_image_requires_matching_archive_hash_id_platform_and_evidence(self):
        manifest=self.manifest()
        config=json.dumps({"os":"linux","architecture":"amd64","config":{"Entrypoint":["/app"],"User":"10001"},"rootfs":{"type":"layers","diff_ids":[]}}).encode()
        config_id=hashlib.sha256(config).hexdigest()
        manifest_type="application/vnd.oci.image.manifest.v1+json"
        body=json.dumps({"schemaVersion":2,"mediaType":manifest_type,"config":{"digest":"sha256:"+config_id,"mediaType":"application/vnd.oci.image.config.v1+json","size":len(config)},"layers":[]}).encode()
        image_id="sha256:"+hashlib.sha256(body).hexdigest()
        archive_path=self.root/"adapter.tar"
        with tarfile.open(archive_path,"w") as archive:
            for name,raw in [("index.json",json.dumps({"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"digest":image_id,"mediaType":manifest_type,"size":len(body)}]}).encode()),
                             ("blobs/sha256/"+image_id[7:],body),("blobs/sha256/"+config_id,config)]:
                member=tarfile.TarInfo(name);member.size=len(raw);archive.addfile(member,io.BytesIO(raw))
        digest=hashlib.sha256(archive_path.read_bytes()).hexdigest()
        component={"kind":"local-image","version":"0.0.0-synthetic","image":image_id,"platform":"linux/amd64",
                   "artifact":{"file":archive_path.name,"sha256":digest}}
        manifest["components"]["adapter"]=component
        evidence=json.loads((self.root/"evidence.json").read_text())
        evidence["observed_components"]["adapter"]={"kind":"local-image","version":component["version"],"image":image_id,"platform":component["platform"],"artifact_sha256":digest}
        path=self.write("evidence.json",evidence);manifest["evidence"]["sha256"]=hashlib.sha256(path.read_bytes()).hexdigest()
        code,report=self.run_cli("manifest",self.write("manifest.json",manifest))
        self.assertEqual(code,0,report);self.assertEqual(report["status"],"QUALIFIED_LOCAL_ONLY")
        for field,value in [("image","sha256:"+"0"*64),("platform","linux/arm64")]:
            invalid=copy.deepcopy(manifest);invalid["components"]["adapter"][field]=value
            self.assertEqual(self.run_cli("manifest",self.write("manifest.json",invalid))[0],2)
        invalid=copy.deepcopy(manifest);invalid["components"]["adapter"]["artifact"]["sha256"]="0"*64
        self.assertEqual(self.run_cli("manifest",self.write("manifest.json",invalid))[0],2)
        invalid=copy.deepcopy(manifest);del invalid["components"]["adapter"]["artifact"]
        self.assertEqual(self.run_cli("manifest",self.write("manifest.json",invalid))[0],2)

    def test_local_image_rejects_config_as_root_even_with_valid_tar_and_id_hash(self):
        from qualify import local_image_artifact, Invalid
        for media_type in ["application/vnd.oci.image.config.v1+json","application/vnd.oci.image.manifest.v1+json"]:
            config=json.dumps({"os":"linux","architecture":"amd64","config":{"Entrypoint":["/app"]},"rootfs":{"type":"layers","diff_ids":[]}}).encode()
            image_id="sha256:"+hashlib.sha256(config).hexdigest()
            archive_path=self.root/"config-only.tar"
            with tarfile.open(archive_path,"w") as archive:
                root=json.dumps({"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"digest":image_id,"mediaType":media_type,"size":len(config)}]}).encode()
                for name,raw in [("index.json",root),("blobs/sha256/"+image_id[7:],config)]:
                    member=tarfile.TarInfo(name);member.size=len(raw);archive.addfile(member,io.BytesIO(raw))
            component={"kind":"local-image","version":"0.0.0-synthetic","image":image_id,"platform":"linux/amd64","artifact":{"file":archive_path.name,"sha256":hashlib.sha256(archive_path.read_bytes()).hexdigest()}}
            with self.assertRaises(Invalid):local_image_artifact(component,self.root/"manifest.json")

    def test_native_postgres_two_part_patch_release_is_supported(self):
        manifest=self.manifest();manifest["components"]["postgres"]["version"]="18.4"
        evidence=json.loads((self.root/"evidence.json").read_text());evidence["observed_components"]["postgres"]["version"]="18.4"
        path=self.write("evidence.json",evidence);manifest["evidence"]["sha256"]=hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(self.run_cli("manifest",self.write("manifest.json",manifest))[0],0)


if __name__ == "__main__":
    unittest.main()
