"""Pure integration mocks; copied v8 core is never executed live."""
import ast
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'ci/engine26-first'))
sys.path.insert(0,str(ROOT/'tests'))
import orchestrate as o
import verify_evidence as v
from test_candidate import good_fixture


class IntegrationV4(unittest.TestCase):
    def test_diagnostic_package_exact_input_hashes(self):
        expected={'engine26_candidate.py':'af2266706383922fcd81a4f26d350e97f726874f8c110bb78eff277b7c25a4d0',
                  'recovery_core36.py':'e0a70497875718db6d08d52a3dcd9609e31de15b1e3b25cc4b75d79af96a6300',
                  'receipt_journal.py':'47c8a9644204d9927b07d74ae4808b1fce930196d178210ab8135f24fb858eba'}
        self.assertEqual(o.HASHES,expected)
        for name,digest in expected.items():self.assertEqual(hashlib.sha256((ROOT/'ci/engine26-first'/name).read_bytes()).hexdigest(),digest)

    def test_failure_event_can_never_pass_success_acceptance(self):
        result,events=good_fixture()
        events.append({'event':'case_failure','sequence':len(events)+1,'case_index':11,'stage':'CAPTURE','code':'PORT_BINDINGS_REJECTED'})
        with self.assertRaises(v.Rejected):v.verify(result,events)

    def test_unallowlisted_stage_cannot_pass(self):
        result,events=good_fixture()
        events.append({'event':'case_stage','sequence':len(events)+1,'stage':'UNALLOWLISTED_SENTINEL'})
        with self.assertRaises(v.Rejected):v.verify(result,events)

    def test_failed_first_round_exports_fixed_case_stage_only(self):
        self.assertTrue(hasattr(v,'summarize_diagnostics'))
        result,events=good_fixture();result=copy.deepcopy(result)
        result.update(error='candidate_execution_failed_review_journal',result_status='BLOCKED',cases=result['cases'][:10])
        events=[dict(event='case',sequence=i+1,case=c,status='pass') for i,c in enumerate(v.CASES[:10])]
        events.extend([{'event':'case_stage','sequence':11,'stage':'CAPTURE'},
                       {'event':'case_failure','sequence':12,'case_index':11,'stage':'CAPTURE','code':'PORT_BINDINGS_REJECTED'}])
        summary=v.summarize_diagnostics(result,events)
        self.assertEqual(summary['diagnostics_status'],'FAILED_WITH_FIXED_DIAGNOSTICS')
        self.assertEqual(summary['case_failure'],{'case_index':11,'stage':'CAPTURE','code':'PORT_BINDINGS_REJECTED'})
        self.assertEqual(summary['result_status'],'BLOCKED')
        self.assertNotIn('intent',json.dumps(summary))

    def test_frozen_cleanup_helpers_unchanged_from_v3(self):
        v3=ROOT/'tests/fixtures/engine26-first-v3-orchestrate.py.txt'
        a=ast.parse(v3.read_text());b=ast.parse((ROOT/'ci/engine26-first/orchestrate.py').read_text())
        names={'trusted_directory','errno_category','has_mount_below','publish_export','cleanup_data_root','copy_safe'}
        def nodes(tree):return {n.name:ast.dump(n,include_attributes=False) for n in ast.walk(tree) if isinstance(n,ast.FunctionDef) and n.name in names}
        self.assertEqual(nodes(a),nodes(b))

    def test_diagnostic_enums_match_frozen_v8_source(self):
        tree=ast.parse((ROOT/'ci/engine26-first/engine26_candidate.py').read_text())
        values={n.targets[0].id:n.value for n in tree.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name)}
        self.assertEqual(v.CASE_STAGES,frozenset(ast.literal_eval(values['CASE_STAGES'].args[0])))
        expected=set(ast.literal_eval(values['CASE_FAILURE_CODES']).values())|{'CONTRACT_REJECTED','LOCAL_IO_FAILURE','UNEXPECTED_EXCEPTION'}
        self.assertEqual(v.FAILURE_CODES,expected)
        pairs=ast.literal_eval(values['RECOVERY_RESULT_CODES'].generators[0].iter)
        self.assertEqual(v.RECOVERY_CODES,{pair[1] for pair in pairs}|{'OTHER_CORE_RESULT'})

    def test_invalid_diagnostics_refuse_raw_fields_codes_and_indexes(self):
        result,base=good_fixture();result.update(error='failure',result_status='BLOCKED',cases=result['cases'][:10])
        prefix=[dict(event='case',sequence=i+1,case=c,status='pass') for i,c in enumerate(v.CASES[:10])]
        original={'event':'case_failure','sequence':11,'case_index':11,'stage':'CAPTURE','code':'PORT_BINDINGS_REJECTED'}
        edits=[{'reason':'PRIVATE_SENTINEL'},{'code':'UNALLOWLISTED_SENTINEL'},{'stage':[]},
               {'case_index':True},{'case_index':10},{'case_index':14}]
        for edit in edits:
            with self.subTest(edit=edit):
                event=dict(original);event.update(edit)
                with self.assertRaises(v.Rejected):v.summarize_diagnostics(result,prefix+[event])

    def test_missing_failure_record_remains_unavailable_and_blocked(self):
        result,_=good_fixture();result.update(error='failure',result_status='BLOCKED',cases=result['cases'][:10])
        events=[dict(event='case',sequence=i+1,case=c,status='pass') for i,c in enumerate(v.CASES[:10])]
        summary=v.summarize_diagnostics(result,events)
        self.assertEqual(summary['diagnostics_status'],'MISSING_CASE_FAILURE')
        self.assertIsNone(summary['case_failure'])
        self.assertFalse(summary['acceptance_granted'])
        with self.assertRaises(v.Rejected):v.verify(result,events)

    def test_unknown_core_result_uses_only_fixed_fallback(self):
        result,events=good_fixture()
        events.append({'event':'recovery_result','sequence':len(events)+1,'stage':'RECOVER','code':'OTHER_CORE_RESULT'})
        self.assertEqual(v.summarize_diagnostics(result,events)['latest_recovery_code'],'OTHER_CORE_RESULT')
        events[-1]['detail']='PRIVATE_SENTINEL'
        with self.assertRaises(v.Rejected):v.summarize_diagnostics(result,events)


if __name__=='__main__':unittest.main(verbosity=2)
