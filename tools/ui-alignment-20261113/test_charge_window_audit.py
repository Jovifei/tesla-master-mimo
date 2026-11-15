import importlib.util
import json
import unittest
from copy import deepcopy
from pathlib import Path
spec=importlib.util.spec_from_file_location('charge_window_audit',Path(__file__).with_name('charge-window-audit.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
audit=module.audit

def fixture():
    return {'schema_version':1,'evidence_class':'synthetic','display_timezone':'Asia/Shanghai',
      'scope_verified':{'account':True,'vehicle':True,'source':True},
      'page_responses':[{'status':200,'requested_page':1,'payload':{'data':{'charges':[
        {'charge_id':0,'start_date':'2026-11-12T23:59:00+08:00','charge_energy_added':0,'vin':'PRIVATE-VIN','access_token':'PRIVATE-TOKEN','latitude':31.23456},
        {'charge_id':1,'start_date':'2026-10-27T00:00:00+08:00','charge_energy_added':None},
        {'charge_id':2,'start_date':'2026-11-13T00:00:00+08:00','charge_energy_added':5}
      ],'meta':{'total_pages':1}}}}], 'ui':{'start_date':'2026-10-01','end_date':'2026-10-27'}}

class AuditTests(unittest.TestCase):
    def test_window_and_non_invented_gaps(self):
        out=audit(fixture());self.assertEqual(list(out['daily_counts']),['2026-10-27','2026-11-12'])
        self.assertEqual(out['latest_returned_date'],'2026-11-13');self.assertIsNone(out['provider_history_complete'])
    def test_zero_stays_distinct_from_missing(self):
        out=audit(fixture());self.assertEqual(out['daily_counts']['2026-11-12']['reported_zero_fields'],1)
        self.assertEqual(out['daily_counts']['2026-10-27']['reported_energy_fields'],0)
    def test_scope_and_evidence_are_mandatory(self):
        for key in ('account','vehicle','source'):
            doc=fixture();doc['scope_verified'][key]=False
            with self.assertRaises(ValueError):audit(doc)
        doc=fixture();doc['evidence_class']='natural-accepted'
        with self.assertRaises(ValueError):audit(doc)
    def test_output_has_no_ids_tokens_positions_or_input_echo(self):
        out=json.dumps(audit(fixture()))
        for secret in ('PRIVATE-VIN','PRIVATE-TOKEN','latitude','31.23456','charge_id','access_token'):
            self.assertNotIn(secret,out)
    def test_api_pagination_never_proves_provider_completeness(self):
        doc=fixture();self.assertTrue(audit(doc)['api_pagination_complete'])
        doc['page_responses'][0]['payload']['data']['meta']['total_pages']=2
        self.assertFalse(audit(doc)['api_pagination_complete'])
        doc['page_responses'].append({'status':500,'requested_page':2,'payload':{'error':'PRIVATE ERROR'}})
        self.assertFalse(audit(doc)['api_pagination_complete']);self.assertNotIn('PRIVATE ERROR',json.dumps(audit(doc)))
    def test_timezone_boundary_and_undated_not_guessed(self):
        doc=fixture();rows=doc['page_responses'][0]['payload']['data']['charges']
        rows[0]['start_date']='2026-11-12T17:00:00Z'
        rows[1]['start_date']='2026-11-10T12:00:00'
        out=audit(doc);self.assertEqual(out['counts']['undated_rows'],1);self.assertEqual(out['daily_counts'],{})
    def test_dedup_is_scoped_and_does_not_mutate_input(self):
        doc=fixture();doc['page_responses'][0]['payload']['data']['charges'].append(deepcopy(doc['page_responses'][0]['payload']['data']['charges'][0]))
        before=deepcopy(doc);out=audit(doc);self.assertEqual(out['counts']['duplicate_rows'],1)
        self.assertEqual(out['counts']['returned_rows'],3);self.assertEqual(doc,before)
    def test_invalid_or_unbounded_ranges_rejected(self):
        for a,b in [('2026-11-13','2026-11-12'),('2020-01-01','2026-11-12'),('2026-02-31','2026-11-12')]:
            with self.assertRaises(ValueError):audit(fixture(),a,b)

if __name__=='__main__':unittest.main()
