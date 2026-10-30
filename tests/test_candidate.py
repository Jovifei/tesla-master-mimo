"""Pure static/mock verification. Never executes a Docker command or opens a socket."""
import ast
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
CI = ROOT / "ci" / "engine26-first"
sys.path.insert(0, str(CI))
import orchestrate as o
import verify_evidence as v


def good_fixture():
    events = []
    def event(name, **fields):
        fields.update(event=name, sequence=len(events)+1)
        events.append(fields)
        return len(events)
    target = "a"*64
    create = event("plan", method="POST", api_kind="container")
    event("receipt", kind="container", id=target, plan=create)
    for index, case in enumerate(v.CASES[:-1], 1):
        event("case", case=case, status="pass", case_index=index, total_cases=13, terminal=False)
    plan = event("plan", method="POST", api_kind="start", intent={"id": target})
    event("unknown", plan=plan, api_kind="start", http_status=500)
    event("case", case=v.CASES[-1], status="pass", case_index=13, total_cases=13,
          terminal=True, expected_start_failure_id=target)
    delete = event("plan", method="DELETE", intent={"id": target})
    event("reply", plan=delete, http_status=204)
    event("container_absent", id=target, verified_404=True)
    marker = {"status": "EXPECTED_START_FAILURE_RECONCILED", "case": v.CASES[-1], "plan": plan,
              "container_id": target, "receipt_plan": create, "exact_id_404_persisted": True,
              "original_unknown_preserved": True, "allocation_still_closed": True}
    event("EXPECTED_START_FAILURE_RECONCILED", **marker)
    result = {"error": None, "result_status": "EXPECTED_START_FAILURE_RECONCILED",
              "port_conflict_order": "competitor-first", "cases": [{"case": c, "status": "pass"} for c in v.CASES],
              "production_access": False, "pulls": False, "application_acceptance": False,
              "unknown_history_preserved": True, "expected_start_failure_reconciliation": marker,
              "cleanup": {"retained": [], "enumeration": False, "cached_image_deleted": False,
                          "known_resources_absent": 1, "unknown_plans": [plan],
                          "container_budget": {"allocation_closed": True, "reserved": 0},
                          "uncertain_posts": [{"plan": plan, "api_kind": "start", "http_status": 500,
                                               "unknown_record_persisted": True}]}}
    return result, events


class StaticTests(unittest.TestCase):
    def test_frozen_bytes(self):
        for name, expected in o.HASHES.items():
            with self.subTest(name=name):
                self.assertEqual(hashlib.sha256((CI/name).read_bytes()).hexdigest(), expected)

    def test_python_syntax_and_inline_36_syntax(self):
        for path in ROOT.rglob("*.py"):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        ast.parse(o.PREFLIGHT, feature_version=(3, 6))
        for name in o.HASHES:
            ast.parse((CI/name).read_text(), feature_version=(3, 6))

    def test_workflow_exact_scope(self):
        import yaml
        workflow = yaml.load((ROOT/".github/workflows/engine26-first.yml").read_text(), Loader=yaml.BaseLoader)
        self.assertEqual(set(workflow["on"]), {"push"})
        self.assertEqual(workflow["on"]["push"]["branches"], ["codex/app-fleet-engine26-ci-20261030"])
        self.assertEqual(workflow["on"]["push"]["paths"], [".github/workflows/engine26-first.yml", "ci/engine26-first/**"])
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(len(workflow["jobs"]), 1)
        job = workflow["jobs"]["first"]
        self.assertEqual(job["runs-on"], "ubuntu-24.04")
        self.assertEqual(job["timeout-minutes"], "20")
        self.assertIn("github.run_attempt == 1", job["if"])
        self.assertNotIn("strategy", job)
        actions = [x for x in job["steps"] if "uses" in x]
        self.assertEqual([x["uses"] for x in actions], [
            "actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683",
            "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"])
        self.assertEqual(actions[0]["with"]["persist-credentials"], "false")
        text = (ROOT/".github/workflows/engine26-first.yml").read_text()
        for forbidden in ("secrets.", "workflow_dispatch", "pull_request", "id-token:", "self-hosted", "services:", "cache@"):
            self.assertNotIn(forbidden, text)

    def test_command_shapes(self):
        root = Path("/root/ml26-ci-1-1")
        dind = o.dind_args(root, root/"dind.cid")
        py = o.python_args(root, "a"*64, root/"python.cid", "1:2", "3", "sha256:"+"b"*64)
        self.assertIn("--network=none", dind)
        self.assertIn("--privileged", dind)
        self.assertNotIn("--entrypoint=dockerd", dind)
        self.assertEqual(dind[-4:], ["dockerd", "--host=unix:///ci-socket/docker.sock", "--data-root=/var/lib/docker", "--storage-driver=overlay2"])
        self.assertIn("--network=container:"+"a"*64, py)
        self.assertIn("--cap-drop=ALL", py)
        self.assertIn("--read-only", py)
        self.assertIn("--user=0:0", py)
        self.assertEqual(sum(x.startswith("type=bind,") for x in py), 5)
        for args in (dind, py):
            text = " ".join(args)
            self.assertNotIn("src=/var/run/docker.sock", text)
            self.assertNotIn("type=volume", text)
            self.assertNotIn("--publish", text)
            self.assertNotIn("--network=host", text)
            self.assertNotIn("--env", text)
        self.assertIn("competitor-first", o.PREFLIGHT)
        self.assertNotIn("recovery-first", o.PREFLIGHT)

    def test_pins_and_no_discovery(self):
        self.assertEqual(len(o.REFS), 3)
        for value in o.REFS.values():
            self.assertRegex(value, r"@sha256:[0-9a-f]{64}$")
        tree = ast.parse((CI/"orchestrate.py").read_text())
        command_lists = [n for n in ast.walk(tree) if isinstance(n, ast.List) and n.elts and isinstance(n.elts[0], ast.Constant)]
        for node in command_lists:
            literals = [e.value for e in node.elts if isinstance(e, ast.Constant) and isinstance(e.value, str)]
            self.assertNotIn("prune", literals)
            self.assertNotIn("ps", literals)
            self.assertNotIn("ls", literals)
            self.assertNotIn("volume", literals)
        self.assertEqual(set(o.HOST_ENV) & {"GITHUB_TOKEN", "ACTIONS_RUNTIME_TOKEN", "DOCKER_HOST", "DOCKER_CONTEXT"}, set())

    def test_soft_budget(self):
        source = (CI/"orchestrate.py").read_text()
        self.assertIn("signal.alarm(840)", source)
        self.assertIn("time.monotonic() + 120", source)
        self.assertIn("timeout=600", source)


class AcceptanceTests(unittest.TestCase):
    def test_expected_unknown_is_preserved_and_accepted(self):
        result, events = good_fixture()
        before = copy.deepcopy((result, events))
        self.assertEqual(v.verify(result, events)["unknown_history_count"], 1)
        self.assertEqual((result, events), before)

    def test_invalid_result_mutations(self):
        mutations = [lambda r: r.update(error="failure"), lambda r: r.update(port_conflict_order="recovery-first"),
                     lambda r: r["cases"].pop(), lambda r: r["cleanup"].update(retained=[{}]),
                     lambda r: r["cleanup"].update(cleanup_failed=True),
                     lambda r: r["cleanup"]["container_budget"].update(allocation_closed=False),
                     lambda r: r["cleanup"]["container_budget"].update(reserved=1),
                     lambda r: r["cleanup"]["uncertain_posts"].append({}),
                     lambda r: r["cleanup"]["uncertain_posts"][0].update(unknown_record_persisted=False),
                     lambda r: r["cleanup"].update(unknown_plans=[]),
                     lambda r: r["cleanup"].update(known_resources_absent=0),
                     lambda r: r["expected_start_failure_reconciliation"].update(allocation_still_closed=False),
                     lambda r: r.update(production_access=True)]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                r, e = good_fixture(); mutate(r)
                with self.assertRaises(v.Rejected): v.verify(r, e)

    def test_invalid_journal_mutations(self):
        mutations = [lambda e: e.append(dict(event="unknown", sequence=len(e)+1, plan=99, api_kind="create", http_status=0)),
                     lambda e: e[1].update(id="b"*64),
                     lambda e: next(x for x in e if x["event"]=="unknown").update(http_status=503),
                     lambda e: next(x for x in e if x["event"]=="reply").update(http_status=404),
                     lambda e: next(x for x in e if x["event"]=="container_absent").update(verified_404=False),
                     lambda e: e[-1].update(event="missing_marker"),
                     lambda e: e[0].update(sequence=100),
                     lambda e: e.append(dict(event="plan", sequence=len(e)+1, method="POST", api_kind="start"))]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                r, e = good_fixture(); mutate(e)
                with self.assertRaises(v.Rejected): v.verify(r, e)


class OrchestrationMocks(unittest.TestCase):
    def runner(self, execute):
        args = SimpleNamespace(run="123", attempt="1", sha="a"*40, evidence=ROOT/"mock-output", source=CI)
        runner = o.Runner(args, execute=execute)
        runner.save_state = lambda: None
        runner.state = {"cleanup": {}}
        return runner

    def test_cgroup_exact_versions_and_exit(self):
        for text in ("v2 memory=16777216\nswap=0\npids=16\n", "v1 memory=16777216\nswap=16777216\npids=16\n"):
            self.assertEqual(o.validate_cgroup(text, 37, 37)["pids"], 16)
        for text, code, inspected in [("v2 memory=max\nswap=0\npids=16\n",37,37),
                                      ("v2 memory=16777216\nswap=0\npids=max\n",37,37),
                                      ("v2 memory=16777216\nswap=0\npids=16\n",0,37),
                                      ("v2 memory=16777216\nswap=0\npids=16\n",37,0)]:
            with self.assertRaises(v.Rejected): o.validate_cgroup(text, code, inspected)

    def test_creation_receipt_must_match(self):
        self.assertEqual(o.receipt_id("a"*64+"\n", "a"*64), "a"*64)
        for stdout, cid in [("a"*12,"a"*12),("a"*64,"b"*64),("a"*64,""),("name","name")]:
            with self.assertRaises(v.Rejected): o.receipt_id(stdout,cid)

    def test_exact_id_cleanup_and_absence(self):
        calls=[]
        def execute(argv, **kwargs):
            calls.append(argv)
            if "rm" in argv: return subprocess.CompletedProcess(argv,0,b"a"*64+b"\n",b"")
            return subprocess.CompletedProcess(argv,1,b"",b"Error: No such object")
        runner = self.runner(execute); runner.state["python"]="a"*64
        runner.remove_container("python"); runner.remove_container("python")
        self.assertEqual(len(calls),2)
        self.assertTrue(all(c[-1]=="a"*64 for c in calls))
        self.assertEqual(runner.state["cleanup"]["python"], "removed_exact_receipt")

    def test_unknown_cleanup_not_retried(self):
        calls=[]
        def execute(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv,1,b"",b"daemon unavailable")
        runner=self.runner(execute);runner.state["dind"]="a"*64
        runner.remove_container("dind");runner.remove_container("dind")
        self.assertEqual(len(calls),1)
        self.assertEqual(runner.state["cleanup"]["dind"],"FAILED_OR_UNKNOWN_no_retry")

    def test_unreceipted_cleanup_does_nothing(self):
        runner=self.runner(lambda *a,**k: self.fail("must not call Docker"))
        runner.remove_container("python")
        self.assertEqual(runner.state["cleanup"],{})

    def test_transport_timeout_is_blocked(self):
        def execute(*args,**kwargs): raise subprocess.TimeoutExpired("mock",1)
        runner=self.runner(execute)
        with self.assertRaises(v.Rejected): runner.command(["start","a"*64])
        self.assertEqual(runner.commands[-1]["status"],"timeout_or_launch_failure")

    def test_env_and_endpoint_are_explicit(self):
        def execute(argv,**kwargs):
            self.assertEqual(argv[:2],["docker","--host=unix:///var/run/docker.sock"])
            self.assertEqual(kwargs["env"],o.HOST_ENV)
            return subprocess.CompletedProcess(argv,0,b"",b"")
        self.runner(execute).command(["inspect","a"*64])

    def test_export_rejects_symlink_hardlink_writable_and_large(self):
        class Source:
            def __init__(self, **changes): self.changes=changes
            def lstat(self):
                values=dict(st_mode=0o100600,st_uid=0,st_nlink=1,st_size=1)
                values.update(self.changes)
                return SimpleNamespace(**values)
        for changes in ({"st_mode":0o120777},{"st_nlink":2},{"st_mode":0o100622},{"st_size":17*1024*1024}):
            with self.subTest(changes=changes):
                with self.assertRaises(v.Rejected): o.validate_export_metadata(Source(**changes).lstat())
        self.assertFalse((ROOT/"must-not-exist").exists())

    def test_missing_health_still_preserves_journal(self):
        output=ROOT/"mock-output";output.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output) as tmp:
            root=Path(tmp);evidence=root/"evidence";evidence.mkdir()
            source=root/"runs"/("ml26"+"a"*32);source.mkdir(parents=True)
            result,events=good_fixture()
            (source/"result.json").write_text(json.dumps(result))
            (source/"journal.jsonl").write_text("\n".join(json.dumps(x) for x in events)+"\n")
            (source/"core-receipts").mkdir()
            (source/"core-receipts"/("a"*32+".planned.json")).write_text("{}")
            runner=self.runner(lambda *a,**k:self.fail("Docker forbidden"))
            runner.root=root;runner.evidence=evidence
            runner.copy_safe=lambda src,dst:shutil.copyfile(src,dst)
            runner.public=lambda *a:None
            with self.assertRaises(v.Rejected):runner.snapshot()
            self.assertTrue((evidence/source.name/"journal.jsonl").exists())
            self.assertFalse(runner.state.get("snapshot_sealed",False))

    def test_finalized_finalizer_never_restarts(self):
        (ROOT/"mock-output").mkdir(exist_ok=True)
        runner=self.runner(lambda *a,**k:self.fail("Docker forbidden"))
        runner.state.update(finalized=True,status="EXPECTED_START_FAILURE_RECONCILED")
        self.assertTrue(runner.finalize())

    def test_changed_data_inode_is_retained(self):
        output=ROOT/"mock-output";output.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output) as tmp:
            root=Path(tmp);data=root/"data";data.mkdir()
            runner=self.runner(lambda *a,**k:self.fail("Docker forbidden"))
            runner.root=root;runner.evidence=root
            runner.public=lambda *a:None;runner.seal_manifest=lambda:None
            runner.state.update(data_root={"path":str(data),"dev":0,"ino":-1},
                                cleanup={x:"removed_exact_receipt" for x in ("dind","python","probe")})
            with patch.object(o.shutil,"rmtree",side_effect=AssertionError("must retain changed data")):
                self.assertFalse(runner.finalize())
            self.assertTrue(data.exists())
            self.assertEqual(runner.state["status"],"BLOCKED")


class ShellMocks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        candidates = [Path(r"D:\Program Files\Git\bin\bash.exe"), Path("/bin/bash")]
        cls.bash = next((str(x) for x in candidates if x.exists()), None)
        if not cls.bash: raise unittest.SkipTest("No local Bash; syntax/exit checks unverified")

    def test_bash_syntax(self):
        result=subprocess.run([self.bash,"--noprofile","--norc","-n",str(CI/"run-first.sh")],capture_output=True)
        self.assertEqual(result.returncode,0,result.stderr.decode(errors="replace"))

    def test_wrapper_preserves_mocked_program_exit_and_rejects_retry(self):
        output=ROOT/"mock-output";output.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output) as tmp:
            sudo=Path(tmp)/"sudo";sudo.write_text("#!/usr/bin/env bash\nexit 37\n",encoding="ascii",newline="\n")
            if os.name!="nt": sudo.chmod(0o755)
            env=dict(os.environ, GITHUB_ACTIONS="true",RUNNER_ENVIRONMENT="github-hosted",RUNNER_OS="Linux",RUNNER_ARCH="X64",
                     GITHUB_EVENT_NAME="push",GITHUB_REF="refs/heads/codex/app-fleet-engine26-ci-20261030",GITHUB_RUN_ATTEMPT="1",
                     GITHUB_RUN_ID="123",GITHUB_SHA="a"*40,GITHUB_WORKSPACE=str(ROOT),PATH=str(tmp)+os.pathsep+os.environ["PATH"])
            first=subprocess.run([self.bash,"--noprofile","--norc",str(CI/"run-first.sh")],env=env,capture_output=True)
            self.assertEqual(first.returncode,37,first.stderr.decode(errors="replace"))
            env["GITHUB_RUN_ATTEMPT"]="2"
            retry=subprocess.run([self.bash,"--noprofile","--norc",str(CI/"run-first.sh")],env=env,capture_output=True)
            self.assertEqual(retry.returncode,65)


if __name__ == "__main__":
    unittest.main(verbosity=2)
