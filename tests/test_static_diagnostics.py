"""Diagnostic-only probes. All events, exceptions and API paths are synthetic."""
import ast
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent.parent
CI=ROOT/'ci/engine26-first'
sys.path.insert(0,str(CI))
import engine26_candidate as h
import recovery_core36 as p
import verify_evidence as v


class MemoryJournal:
    def __init__(self):
        self.events=[dict(event='case',sequence=i+1,case=case,status='pass') for i,case in enumerate(v.CASES[:10])]
    def append(self,event,**fields):
        record=dict(fields,event=event,sequence=len(self.events)+1)
        self.events.append(record)
        return record['sequence']


class StaticDiagnostics(unittest.TestCase):
    def record(self,error,stage='CAPTURE'):
        suite=h.LiveSuite.__new__(h.LiveSuite)
        journal=MemoryJournal()
        suite.e=SimpleNamespace(journal=journal);suite._case_stage=stage
        with contextlib.redirect_stdout(io.StringIO()) as output:
            suite._record_case_failure(11,error)
        return journal.events,output.getvalue()

    def test_distinct_static_reasons_get_distinct_safe_codes(self):
        reasons={'api_not_allowlisted':'API_NOT_ALLOWLISTED',
                 'original_creation_receipt_required':'ORIGINAL_CREATION_RECEIPT_REQUIRED',
                 'unresolved_existing_endpoint':'UNRESOLVED_EXISTING_ENDPOINT',
                 'network_identity_changed':'NETWORK_IDENTITY_CHANGED',
                 'network_mode_mapping_incomplete':'NETWORK_MODE_MAPPING_INCOMPLETE',
                 'unknown_config_field':'CONFIG_FIELDS_REJECTED',
                 'unsupported_hostconfig_value':'HOSTCONFIG_VALUE_REJECTED',
                 'mount_mapping_incomplete':'MOUNT_MAPPING_INCOMPLETE',
                 'resource_unavailable':'RESOURCE_UNAVAILABLE'}
        for reason,expected in reasons.items():
            with self.subTest(reason=reason):
                events,output=self.record(h.Blocked(reason))
                self.assertEqual(events[-1]['code'],expected)
                self.assertIn(expected,output)

    def test_actual_guard_name_path_still_rejected_and_diagnosed(self):
        guard=h.GuardedEngine.__new__(h.GuardedEngine)
        with self.assertRaises(h.Blocked) as caught:
            guard._route('GET','/networks/synthetic-network-name',None)
        self.assertEqual(caught.exception.args,('api_not_allowlisted',))
        events,_=self.record(caught.exception)
        self.assertEqual(events[-1]['code'],'API_NOT_ALLOWLISTED')

    def test_legacy_and_new_codes_both_verify_as_blocked_diagnostics(self):
        for error,code in ((h.Blocked('unsupported_port_binding'),'PORT_BINDINGS_REJECTED'),
                           (h.Blocked('api_not_allowlisted'),'API_NOT_ALLOWLISTED')):
            events,_=self.record(error)
            result={'result_status':'BLOCKED','error':'failure',
                    'cases':[dict(case=case,status='pass') for case in v.CASES[:10]]}
            summary=v.summarize_diagnostics(result,events)
            self.assertEqual(summary['case_failure']['code'],code)
            self.assertFalse(summary['acceptance_granted'])
            with self.assertRaises(v.Rejected):v.verify(result,events)

    def test_unknown_reasons_never_echo_env_paths_credentials_or_text(self):
        secret='SYNTHETIC_ENV_SENTINEL=PRIVATE /private/path token=FAKE_CREDENTIAL'
        cases=[(h.Blocked(secret),'CONTRACT_REJECTED'),
               (p.RecoveryBlocked(secret),'CONTRACT_REJECTED'),
               (h.Blocked('api_not_allowlisted '+secret),'CONTRACT_REJECTED'),
               (h.Blocked({'reason':secret}),'CONTRACT_REJECTED'),
               (h.Blocked(secret,secret),'CONTRACT_REJECTED'),
               (OSError(5,secret),'LOCAL_IO_FAILURE'),
               (RuntimeError(secret),'UNEXPECTED_EXCEPTION'),
               (KeyboardInterrupt(secret),'UNEXPECTED_EXCEPTION')]
        for error,code in cases:
            with self.subTest(error_type=type(error).__name__,code=code):
                events,output=self.record(error)
                self.assertEqual(events[-1]['code'],code)
                serialized=json.dumps(events)+output
                for marker in ('SYNTHETIC_ENV_SENTINEL','/private/path','FAKE_CREDENTIAL','Traceback'):
                    self.assertNotIn(marker,serialized)
                self.assertEqual(set(events[-1]),{'event','sequence','case_index','stage','code'})

    def test_core_contract_reasons_are_mapped_without_raw_reason_fields(self):
        for reason in ('network_identity_changed','unresolved_existing_endpoint','unknown_hostconfig_field'):
            events,output=self.record(p.RecoveryBlocked(reason))
            self.assertEqual(events[-1]['code'],h.CASE_FAILURE_CODES[reason])
            self.assertNotIn(reason,output)
            self.assertNotIn('reason',events[-1])

    def test_unavailable_journal_still_emits_only_fixed_stdout_once(self):
        calls=[]
        def fail(*args,**kwargs):calls.append((args,kwargs));raise OSError(5,'PRIVATE_PATH_TOKEN_SENTINEL')
        suite=h.LiveSuite.__new__(h.LiveSuite);suite._case_stage='CAPTURE';suite.e=SimpleNamespace(journal=SimpleNamespace(append=fail))
        with contextlib.redirect_stdout(io.StringIO()) as output:
            suite._record_case_failure(11,h.Blocked('api_not_allowlisted'))
        self.assertEqual(len(calls),1)
        self.assertIn('UNAVAILABLE',output.getvalue())
        self.assertIn('API_NOT_ALLOWLISTED',output.getvalue())
        self.assertNotIn('PRIVATE_PATH_TOKEN_SENTINEL',output.getvalue())

    def test_unknown_stage_uses_fixed_case_body(self):
        events,output=self.record(h.Blocked('api_not_allowlisted'),stage='PRIVATE_STAGE_SENTINEL')
        self.assertEqual(events[-1]['stage'],'CASE_BODY')
        self.assertNotIn('PRIVATE_STAGE_SENTINEL',output)

    def test_three_added_reasons_require_exact_sensitive_free_match(self):
        for reason in ('unsupported_hostconfig_value','mount_mapping_incomplete','resource_unavailable'):
            for cls in (h.Blocked,p.RecoveryBlocked):
                with self.subTest(reason=reason,error=cls.__name__):
                    events,_=self.record(cls(reason))
                    self.assertEqual(events[-1]['code'],h.CASE_FAILURE_CODES[reason])
                    forged=reason+' ENV_SENTINEL=/private/path CREDENTIAL_SENTINEL'
                    events,output=self.record(cls(forged))
                    self.assertEqual(events[-1]['code'],'CONTRACT_REJECTED')
                    for marker in ('ENV_SENTINEL','/private/path','CREDENTIAL_SENTINEL'):
                        self.assertNotIn(marker,json.dumps(events)+output)

    def test_only_static_diagnostic_assignments_change(self):
        def normalized(path,exclude):
            tree=ast.parse(path.read_text())
            tree.body=[node for node in tree.body if not(isinstance(node,ast.Assign) and
                len(node.targets)==1 and isinstance(node.targets[0],ast.Name) and node.targets[0].id==exclude)]
            return ast.dump(tree,include_attributes=False)
        for name,assignment in (('engine26_candidate.py','CASE_FAILURE_CODES'),
                                ('orchestrate.py','HASHES'),('verify_evidence.py','FAILURE_CODES')):
            with self.subTest(file=name):
                baseline=ROOT/'tests/fixtures'/('diagnostic-baseline-'+name+'.txt')
                self.assertEqual(normalized(CI/name,assignment),normalized(baseline,assignment))
        self.assertEqual(hashlib.sha256((CI/'recovery_core36.py').read_bytes()).hexdigest(),
                         'e0a70497875718db6d08d52a3dcd9609e31de15b1e3b25cc4b75d79af96a6300')
        self.assertEqual(hashlib.sha256((CI/'receipt_journal.py').read_bytes()).hexdigest(),
                         '47c8a9644204d9927b07d74ae4808b1fce930196d178210ab8135f24fb858eba')

    def test_old_map_and_verifier_codes_are_unchanged_subsets(self):
        def assignment(path,name):
            tree=ast.parse(path.read_text())
            return next(node.value for node in tree.body if isinstance(node,ast.Assign) and
                        isinstance(node.targets[0],ast.Name) and node.targets[0].id==name)
        baseline=ROOT/'tests/fixtures/diagnostic-baseline-engine26_candidate.py.txt'
        old=ast.literal_eval(assignment(baseline,'CASE_FAILURE_CODES'))
        for reason,code in old.items():self.assertEqual(h.CASE_FAILURE_CODES[reason],code)
        baseline=ROOT/'tests/fixtures/diagnostic-baseline-verify_evidence.py.txt'
        old_codes=set(ast.literal_eval(assignment(baseline,'FAILURE_CODES').args[0]))
        self.assertLessEqual(old_codes,v.FAILURE_CODES)
        self.assertEqual(v.FAILURE_CODES,set(h.CASE_FAILURE_CODES.values())|
                         {'CONTRACT_REJECTED','LOCAL_IO_FAILURE','UNEXPECTED_EXCEPTION'})

    def test_verifier_refuses_arbitrary_new_or_raw_diagnostic_fields(self):
        events,_=self.record(h.Blocked('api_not_allowlisted'))
        result={'result_status':'BLOCKED','error':'failure','cases':[dict(case=case,status='pass') for case in v.CASES[:10]]}
        events[-1]['code']='UNREGISTERED_CODE_SENTINEL'
        with self.assertRaises(v.Rejected):v.summarize_diagnostics(result,events)
        events[-1]['code']='API_NOT_ALLOWLISTED';events[-1]['exception']='PRIVATE_SENTINEL'
        with self.assertRaises(v.Rejected):v.summarize_diagnostics(result,events)


if __name__=='__main__':unittest.main(verbosity=2)
