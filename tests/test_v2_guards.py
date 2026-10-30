"""Regression probes for v2 review findings; entirely mocked, no Docker/socket."""
import contextlib
import io
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import mock_open, patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "ci" / "engine26-first"))
import orchestrate as o


class V2Guards(unittest.TestCase):
    def runner(self, execute):
        args = SimpleNamespace(run="123", attempt="1", sha="a"*40, evidence=ROOT/"mock-output", source=ROOT/"ci/engine26-first")
        runner = o.Runner(args, execute=execute)
        runner.state={"cleanup":{}}
        runner.save_state=lambda:None
        runner.public=lambda *a:None
        runner.seal_manifest=lambda:None
        return runner

    def test_expired_budget_never_dispatches_docker(self):
        calls=[]
        runner=self.runner(lambda *a,**k:(calls.append(a) or subprocess.CompletedProcess(a,0,b"",b"")))
        runner.deadline=99
        with patch.object(o.time,"monotonic",return_value=100):
            with self.assertRaises(BaseException):runner.command(["inspect","a"*64])
        self.assertEqual(calls,[])

    def test_finalize_file_phase_past_global_deadline_cannot_pass(self):
        (ROOT/"mock-output").mkdir(exist_ok=True)
        clock=[0]
        runner=self.runner(lambda *a,**k:self.fail("Docker forbidden"))
        def file_phase():clock[0]=121;return True
        with patch.object(o.time,"monotonic",side_effect=lambda:clock[0]), \
             patch.object(runner,"_finalize_body",side_effect=file_phase,create=True) as body:
            self.assertFalse(runner.finalize())
            self.assertEqual(body.call_count,1)
        self.assertEqual(runner.state["status"],"BLOCKED")

    def export_mocks(self, chunks, limit=16*1024*1024):
        metadata=SimpleNamespace(st_mode=0o100600,st_uid=0,st_gid=0,st_nlink=1,st_size=6,st_dev=1,st_ino=2)
        @contextlib.contextmanager
        def trusted(_path):
            yield SimpleNamespace(fd=55,verify=lambda:None)
        stack=contextlib.ExitStack()
        stack.enter_context(patch.object(o.os,"O_NOFOLLOW",0x20000,create=True))
        stack.enter_context(patch.object(o.os,"O_CLOEXEC",0x80000,create=True))
        stack.enter_context(patch.object(o,"MAX_EVIDENCE_BYTES",limit,create=True))
        stack.enter_context(patch.object(o,"trusted_directory",trusted,create=True))
        stack.enter_context(patch.object(Path,"lstat",return_value=metadata))
        opened=stack.enter_context(patch.object(o.os,"open",return_value=77))
        stack.enter_context(patch.object(o.os,"fstat",return_value=metadata))
        stack.enter_context(patch.object(o.os,"stat",return_value=metadata))
        stack.enter_context(patch.object(o.os,"read",side_effect=chunks))
        stack.enter_context(patch.object(o.os,"close"))
        stack.enter_context(patch.object(o.os,"fsync"))
        stack.enter_context(patch.object(o.os,"chmod"))
        stack.enter_context(patch("builtins.open",mock_open(read_data=b"public")))
        publish=stack.enter_context(patch.object(o,"publish_export",create=True))
        return stack,opened,publish

    def test_export_binds_read_to_nofollow_dirfd_and_fstat(self):
        stack,opened,publish=self.export_mocks([b"public",b""])
        with stack:
            o.Runner.copy_safe(Path("/root/job/runs/ml26abc/result.json"),ROOT/"mock-output/result.json")
            self.assertTrue(opened.called,"plain source open is not a descriptor-bound read")
            args,kwargs=opened.call_args
            self.assertEqual(args[0],"result.json")
            self.assertEqual(kwargs["dir_fd"],55)
            self.assertTrue(args[1] & getattr(o.os,"O_NOFOLLOW",0),"O_NOFOLLOW required")
            publish.assert_called_once_with(ROOT/"mock-output/result.json",b"public")

    def test_growing_source_is_bounded_before_any_export(self):
        stack,opened,publish=self.export_mocks([b"x"*9,b""],limit=8)
        with stack:
            with self.assertRaises(o.Rejected):
                o.Runner.copy_safe(Path("/root/job/runs/ml26abc/result.json"),ROOT/"mock-output/result.json")
            publish.assert_not_called()

    def test_replaced_leaf_is_rejected_before_export(self):
        stack,opened,publish=self.export_mocks([b"public",b""])
        changed=SimpleNamespace(st_mode=0o100600,st_uid=0,st_gid=0,st_nlink=1,st_size=6,st_dev=1,st_ino=999)
        with stack,patch.object(o.os,"stat",return_value=changed):
            with self.assertRaises(o.Rejected):
                o.Runner.copy_safe(Path("/root/job/runs/ml26abc/result.json"),ROOT/"mock-output/result.json")
            publish.assert_not_called()

    def test_partial_publish_has_no_visible_artifact(self):
        directory=SimpleNamespace(st_mode=0o40755,st_uid=0)
        with patch.object(o.os,"O_DIRECTORY",0x10000,create=True), \
             patch.object(o.os,"O_NOFOLLOW",0x20000,create=True), \
             patch.object(o.os,"O_CLOEXEC",0x80000,create=True), \
             patch.object(o.os,"open",side_effect=[55,66]), \
             patch.object(o.os,"fstat",return_value=directory), \
             patch.object(o.os,"write",side_effect=OSError("injected write failure")), \
             patch.object(o.os,"close"),patch.object(o.os,"unlink") as unlink, \
             patch.object(o.os,"link") as link:
            with self.assertRaises(OSError):o.publish_export(ROOT/"mock-output/result.json",b"public")
            link.assert_not_called()
            self.assertTrue(unlink.call_args.args[0].startswith(".result.json.partial-"))
            self.assertEqual(unlink.call_args.kwargs["dir_fd"],55)

    def test_watchdog_interrupts_noncommand_phase(self):
        (ROOT/"mock-output").mkdir(exist_ok=True)
        runner=self.runner(lambda *a,**k:self.fail("Docker forbidden"))
        handlers=[]
        def register(_sig,handler):handlers.append(handler);return "old-handler"
        with patch.object(o.sys,"platform","linux"), \
             patch.object(o.signal,"SIGALRM",99,create=True), \
             patch.object(o.signal,"ITIMER_REAL",100,create=True), \
             patch.object(o.signal,"signal",side_effect=register), \
             patch.object(o.signal,"setitimer",create=True) as timer, \
             patch.object(runner,"_finalize_body",side_effect=lambda:handlers[0](99,None)):
            self.assertFalse(runner.finalize())
            self.assertEqual(runner.state["status"],"BLOCKED")
            self.assertTrue(runner.state["finalizer_timed_out"])
            self.assertLessEqual(timer.call_args_list[0].args[1],120)
            self.assertEqual(timer.call_args_list[-1].args,(100,0))

    def test_always_cannot_promote_failed_first_step(self):
        (ROOT/"mock-output").mkdir(exist_ok=True)
        runner=self.runner(lambda *a,**k:self.fail("Docker forbidden"))
        runner.recovery_only=True
        runner.state.update(finalized=True,status="EXPECTED_START_FAILURE_RECONCILED",
                            cleanup={x:"removed_exact_receipt" for x in ("dind","python","probe","data_root")})
        self.assertFalse(runner.finalize())
        self.assertEqual(runner.state["status"],"BLOCKED")


if __name__=="__main__":unittest.main(verbosity=2)
