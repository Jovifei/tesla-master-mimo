"""Mock-only cleanup compatibility/safety regressions; no daemon or real removal."""
import contextlib
import errno
import io
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'ci/engine26-first'))
import orchestrate as o


class CleanupV3(unittest.TestCase):
    def exercise(self, mode=0o710, uid=0, gid=0, inode=2, mounted=False, removal_error=None,
                 dind_removed=True, path_drift=False, parent_error=False, target_error=None):
        args=SimpleNamespace(run='123',attempt='1',sha='a'*40,evidence=ROOT/'mock-output',source=ROOT/'ci/engine26-first')
        runner=o.Runner(args,execute=lambda *a,**k:self.fail('No Docker permitted'))
        runner.root=Path('/root/ml26-ci-123-1')
        runner.save_state=lambda:None;runner.public=lambda *a:None;runner.seal_manifest=lambda:None
        runner.state={'cleanup':{x:'removed_exact_receipt' for x in ('python','dind','probe')},
                      'data_root':{'path':str(runner.root/'data'),'dev':1,'ino':2}}
        if not dind_removed:runner.state['cleanup']['dind']='FAILED_OR_UNKNOWN_no_retry'
        if path_drift:runner.state['data_root']['path']=str(runner.root/'other-data')
        metadata=SimpleNamespace(st_mode=0o40000|mode,st_uid=uid,st_gid=gid,st_dev=1,st_ino=inode,st_nlink=2)
        gone=[False]
        @contextlib.contextmanager
        def bound(*args,**kwargs):
            if parent_error:raise o.Rejected('DR_PARENT_PRIVATE_ROOT')
            yield SimpleNamespace(fd=55,verify=lambda:None)
        def remove(*args,**kwargs):
            if removal_error:raise removal_error
            gone[0]=True
        def named(*args,**kwargs):
            if gone[0]:raise FileNotFoundError(errno.ENOENT,'mock')
            return metadata
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(Path,'lstat',return_value=metadata))
            stack.enter_context(patch.object(Path,'resolve',lambda self,*a,**k:self))
            stack.enter_context(patch.object(Path,'is_symlink',return_value=False))
            stack.enter_context(patch.object(Path,'exists',side_effect=lambda: not gone[0]))
            stack.enter_context(patch.object(o,'trusted_directory',bound))
            stack.enter_context(patch.object(o,'has_mount_below',return_value=mounted,create=True))
            for name,value in [('O_DIRECTORY',0x10000),('O_NOFOLLOW',0x20000),('O_CLOEXEC',0x80000)]:
                stack.enter_context(patch.object(o.os,name,value,create=True))
            stack.enter_context(patch.object(o.os,'open',return_value=77,side_effect=target_error))
            stack.enter_context(patch.object(o.os,'fstat',return_value=metadata))
            stack.enter_context(patch.object(o.os,'stat',side_effect=named))
            stack.enter_context(patch.object(o.os,'close'))
            rmtree=stack.enter_context(patch.object(o.shutil,'rmtree',side_effect=remove))
            rmtree.avoids_symlink_attacks=True
            runner._finalize_body()
            return runner.state,list(rmtree.call_args_list)

    def test_official_final_0710_is_allowed_after_receipt_gates(self):
        state,calls=self.exercise()
        self.assertEqual(state['cleanup']['data_root'],'removed_exact_receipt')
        self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args,('data',))
        self.assertEqual(calls[0].kwargs,{'dir_fd':55})

    def test_active_mount_is_refused_before_removal(self):
        state,calls=self.exercise(mode=0o700,mounted=True)
        self.assertEqual(calls,[])
        self.assertEqual(state['cleanup']['data_root'],'FAILED_OR_UNKNOWN_no_retry')

    def test_refusal_records_fixed_stage_without_private_values(self):
        state,calls=self.exercise(mode=0o755)
        self.assertEqual(calls,[])
        detail=state.get('cleanup_details',{}).get('data_root',{})
        self.assertEqual(detail.get('stage'),'DR_MODE_ALLOWLIST')
        self.assertEqual(detail.get('errno_category'),'POLICY_REJECTED')
        self.assertNotIn('/root',str(detail))

    def test_only_0700_and_0710_are_accepted(self):
        for mode in (0o700,0o710):
            with self.subTest(allowed=oct(mode)):
                state,calls=self.exercise(mode=mode)
                self.assertEqual(state['cleanup']['data_root'],'removed_exact_receipt')
        for mode in (0o711,0o701,0o750,0o755,0o770,0o777):
            with self.subTest(refused=oct(mode)):
                state,calls=self.exercise(mode=mode)
                self.assertEqual(calls,[])
                self.assertEqual(state['cleanup']['data_root'],'FAILED_OR_UNKNOWN_no_retry')

    def test_owner_inode_path_and_parent_drift_are_refused(self):
        for args in ({'uid':1000},{'gid':1000},{'inode':999},{'path_drift':True},
                     {'parent_error':True},{'dind_removed':False}):
            with self.subTest(args=args):
                state,calls=self.exercise(**args)
                self.assertEqual(calls,[])
                self.assertEqual(state['cleanup']['data_root'],'FAILED_OR_UNKNOWN_no_retry')

    def test_io_failures_keep_fixed_errno_categories(self):
        for value,category in ((errno.EIO,'EIO'),(errno.EPERM,'EACCES_EPERM'),(errno.EBUSY,'EBUSY')):
            with self.subTest(errno=value):
                state,calls=self.exercise(removal_error=OSError(value,'private path/config must not escape'))
                detail=state['cleanup_details']['data_root']
                self.assertEqual(detail,{'stage':'DR_REMOVE_FD','status':'REFUSED_OR_FAILED','errno_category':category})
                self.assertEqual(state['cleanup']['data_root'],'FAILED_OR_UNKNOWN_no_retry')
        state,calls=self.exercise(target_error=OSError(errno.ELOOP,'private target'))
        self.assertEqual(calls,[])
        self.assertEqual(state['cleanup_details']['data_root']['errno_category'],'SYMLINK_OR_TYPE')

    def test_mountinfo_exact_or_descendant_only(self):
        target=Path('/root/ml26-ci-123-1/data')
        baseline=b'24 1 0:1 / / rw - ext4 /dev/root rw\n'
        for mount,expected in (('/root/ml26-ci-123-1/data',True),
                               ('/root/ml26-ci-123-1/data/overlay2/x',True),
                               ('/root/ml26-ci-123-1/data-other',False)):
            content=baseline+('25 24 0:1 / '+mount+' rw - ext4 /dev/root rw\n').encode()
            with patch('builtins.open',return_value=io.BytesIO(content)):
                self.assertEqual(o.has_mount_below(target),expected)
        with patch('builtins.open',return_value=io.BytesIO(b'invalid')):
            with self.assertRaises(o.Rejected):o.has_mount_below(target)

    def test_private_parent_chain_requires_root_root_0700(self):
        path=Path('/root/ml26-ci-123-1')
        def run(gid=0,mode=0o700):
            metas={100:SimpleNamespace(st_mode=0o40755,st_uid=0,st_gid=0,st_dev=1,st_ino=1,st_nlink=2),
                   101:SimpleNamespace(st_mode=0o40700,st_uid=0,st_gid=0,st_dev=1,st_ino=2,st_nlink=2),
                   102:SimpleNamespace(st_mode=0o40000|mode,st_uid=0,st_gid=gid,st_dev=1,st_ino=3,st_nlink=2)}
            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.object(Path,'is_absolute',return_value=True))
                for key,value in [('O_DIRECTORY',0x10000),('O_NOFOLLOW',0x20000),('O_CLOEXEC',0x80000)]:
                    stack.enter_context(patch.object(o.os,key,value,create=True))
                stack.enter_context(patch.object(o.os,'open',side_effect=[100,101,102]))
                stack.enter_context(patch.object(o.os,'fstat',side_effect=lambda fd:metas[fd]))
                stack.enter_context(patch.object(o.os,'stat',side_effect=lambda name,dir_fd,**kw:metas[101 if dir_fd==100 else 102]))
                close=stack.enter_context(patch.object(o.os,'close'))
                with o.trusted_directory(path,private=True) as held:held.verify()
                self.assertEqual(close.call_count,3)
        run()
        with self.assertRaises(o.Rejected):run(gid=1)
        with self.assertRaises(o.Rejected):run(mode=0o710)


if __name__=='__main__':unittest.main(verbosity=2)
