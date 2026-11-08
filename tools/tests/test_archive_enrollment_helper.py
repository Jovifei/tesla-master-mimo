import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('archive_helper',Path(__file__).parents[1]/'archive_enrollment_helper.py')
helper=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(helper)

class Response:
    status=200
    def __enter__(self):return self
    def __exit__(self,*args):return False
    def read(self,n):return b'{"data":{}}'
class Opener:
    def __init__(self):self.calls=[]
    def open(self,req,timeout):self.calls.append(req);return Response()

class ArchiveHelperTest(unittest.TestCase):
    def setUp(self):
        root=Path(os.environ.get('ARCHIVE_HELPER_TEST_ROOT', r'E:\Claude_allow\Download\matelink-fleet-recovery-20261029\helper-tests'))
        root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=root)
        self.root=Path(self.temp.name)
        self.allowed=patch.object(helper,'ALLOWED',self.root);self.allowed.start()
        self.acl=patch.object(helper,'protect');self.protect=self.acl.start()
        self.bridge=patch.object(helper,'bridge_snapshot',return_value={'image':'synthetic:bridge','image_id':'sha256:'+'a'*64,'runtime_sha256':'c'*64,'mounts_sha256':'synthetic-mounts'});self.bridge.start();self.addCleanup(self.bridge.stop)
        self.addCleanup(self.allowed.stop);self.addCleanup(self.acl.stop);self.addCleanup(self.temp.cleanup)
        self.scope={'vehicle_id':7,'source_instance_id':'synthetic-collector','source_vehicle_id':'2'}
        self.compose={'services':{helper.SERVICE:{'image':'synthetic:bridge','environment':{
            'ARCHIVE_VEHICLE_ID':'7','ARCHIVE_SOURCE_INSTANCE_ID':'synthetic-collector',
            'ARCHIVE_SOURCE_VEHICLE_ID':'2','ARCHIVE_URL':helper.API,
            'ARCHIVE_TOKEN':'SYNTHETIC_OLD_ONLY','STATE_FILE':'/state/state.json','DATABASE_URL':'synthetic-test-only'},
            'volumes':['synthetic-state:/state']}}}
        self.composepath=self.root/'existing-private'/'compose.json';self.composepath.parent.mkdir()
        self.composepath.write_text(json.dumps(self.compose))
        self.statepath=self.root/'request-private'/'state.json'
    def prepare(self):
        data={'handle':'a'*64,'device_secret':'D'*43,'verification_code':'001234','expires_at':'2099-01-01T00:00:00Z'}
        with patch.object(helper,'request',return_value=(200,data)):
            return helper.prepare(self.composepath,self.statepath)
    def test_prepare_exposes_only_public_comparison_and_keeps_login_out(self):
        summary=self.prepare();state=helper.read_json(self.statepath)
        self.assertNotIn('D'*43,json.dumps(summary))
        self.assertNotIn('SYNTHETIC_OLD_ONLY',json.dumps(state))
        self.assertEqual(helper.scope_fingerprint(self.scope),state['scope_fingerprint'])
        self.assertEqual('001234',summary['verification_code'])
    def test_existing_compose_parent_acl_is_not_modified(self):
        helper.write_private(self.composepath,b'{}')
        self.assertFalse(any(call.args[0]==self.composepath.parent for call in self.protect.call_args_list))
    def test_interrupted_private_temporary_does_not_block_fresh_atomic_write(self):
        stale=self.composepath.with_name(self.composepath.name+'.new')
        stale.write_bytes(b'INCOMPLETE_SYNTHETIC_WRITE')
        try:
            helper.write_private(self.composepath,b'{"recovered":true}')
        except helper.FlowError:
            self.fail('a prior process temporary must not prevent a fresh exclusive atomic write')
        self.assertEqual(b'{"recovered":true}',self.composepath.read_bytes())
        self.assertEqual(b'INCOMPLETE_SYNTHETIC_WRITE',stale.read_bytes())
    def test_failed_atomic_replace_keeps_destination_and_cleans_only_own_temporary(self):
        stale=self.composepath.with_name(self.composepath.name+'.old.new');stale.write_bytes(b'PREVIOUS_SYNTHETIC_WRITE')
        before=self.composepath.read_bytes()
        with patch.object(helper.os,'replace',side_effect=OSError('fixture_replace_failure')):
            with self.assertRaises(OSError):helper.write_private(self.composepath,b'NEW_SYNTHETIC_VALUE')
        self.assertEqual(before,self.composepath.read_bytes())
        self.assertEqual([stale],list(self.composepath.parent.glob('*.new')))
    def test_prefix_lookalike_endpoint_is_rejected_without_http(self):
        opener=Opener()
        with self.assertRaises(helper.FlowError):helper.request('/api/v1/archive/enrollments-foreign',{},opener)
        self.assertEqual([],opener.calls)
    def test_archive_url_accepts_only_the_fixed_https_base_origin(self):
        env=self.compose['services'][helper.SERVICE]['environment']
        env['ARCHIVE_URL']=helper.API
        try:
            actual=helper.scope_of(self.compose)
        except helper.FlowError:
            self.fail('bridge base-origin URL must be accepted before it appends the import path')
        self.assertEqual(self.scope,actual)
    def test_archive_url_rejects_preappended_path_and_url_components(self):
        env=self.compose['services'][helper.SERVICE]['environment']
        for invalid in (helper.API+'/api/v1/cars/7/history/archive/import',helper.API+'/',
                        helper.API+'?query=1',helper.API+'#fragment',helper.API.replace('https://','http://'),
                        helper.API.replace('https://','https://user:pass@'),helper.API+':443'):
            with self.subTest(kind=invalid.split('://')[0]):
                env['ARCHIVE_URL']=invalid
                with self.assertRaises(helper.FlowError):helper.scope_of(self.compose)
    def test_scope_change_stops_before_claim(self):
        self.prepare();self.composepath.write_text('{}')
        with patch.object(helper,'request') as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()
    def test_pending_hash_never_accepts_external_compose_changes(self):
        self.prepare();state=helper.read_json(self.statepath)
        intended=json.loads(json.dumps(self.compose))
        intended['services'][helper.SERVICE]['environment']['ARCHIVE_TOKEN']='SYNTHETIC_NEW_CREDENTIAL_ONLY'
        encoded=(json.dumps(intended,ensure_ascii=False,indent=2)+'\n').encode()
        state['pending_compose_sha256']=helper.digest(encoded);helper.save_state(self.statepath,state)
        intended['services'][helper.SERVICE]['environment']['STATE_FILE']='/state/external-change.json'
        self.composepath.write_text(json.dumps(intended))
        with patch.object(helper,'request') as request:
            with self.assertRaisesRegex(helper.FlowError,'^production_compose_changed$'):helper.claim_and_install(self.statepath)
            request.assert_not_called()
    def test_bridge_mount_change_stops_before_claim(self):
        self.prepare()
        with patch.object(helper,'bridge_snapshot',return_value={'image':'synthetic:bridge','image_id':'sha256:'+'a'*64,'mounts_sha256':'changed'}),patch.object(helper,'request') as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()
    def test_open_app_rejects_another_offline_target(self):
        self.prepare();calls=[]
        def run(args):
            calls.append(args)
            if args[-1] == 'devices': return 'List of devices attached\nONE\tdevice\nTWO\toffline'
            return ''
        with patch.object(helper, 'run', side_effect=run):
            with self.assertRaises(helper.FlowError): helper.open_app(self.statepath, 'adb')
        self.assertFalse(any('am' in call for call in calls))
    def test_pending_never_installs_or_acknowledges(self):
        self.prepare()
        with patch.object(helper,'request',return_value=(202,{'state':'pending'})) as request,patch.object(helper,'install_credential') as install:
            self.assertEqual('waiting_for_owner_confirmation',helper.claim_and_install(self.statepath)['phase'])
            install.assert_not_called();self.assertEqual(1,request.call_count)
    def test_wrong_issued_scope_is_not_installed(self):
        self.prepare()
        with patch.object(helper,'request',return_value=(200,{'state':'issued','scope_fingerprint':'wrong','credential':'SYNTHETIC_NEW_ONLY'})),patch.object(helper,'install_credential') as install:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            install.assert_not_called()
    def test_installed_state_is_locally_idempotent_without_secret(self):
        self.prepare();state=helper.read_json(self.statepath);state.pop('device_secret');state['phase']='installed_waiting_for_natural_upload';helper.save_state(self.statepath,state)
        with patch.object(helper,'request') as request:
            self.assertEqual('installed_waiting_for_natural_upload',helper.claim_and_install(self.statepath)['phase'])
            request.assert_not_called()
    def test_install_changes_only_credential_and_preserves_scope(self):
        self.prepare();state=helper.read_json(self.statepath);calls=[]
        def run(args):
            calls.append(args)
            if '--format' in args:return 'true'
            return ''
        with patch.object(helper,'container_identity',return_value=['synthetic-id',helper.PROJECT,helper.SERVICE,state['bridge_image'],state['bridge_image_id']]),patch.object(helper,'run',side_effect=run):
            helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        actual=json.loads(self.composepath.read_text());old=self.compose['services'][helper.SERVICE]
        expected=json.loads(json.dumps(self.compose));expected['services'][helper.SERVICE]['environment']['ARCHIVE_TOKEN']='SYNTHETIC_NEW_CREDENTIAL_ONLY'
        self.assertEqual(expected,actual)
        self.assertNotIn('SYNTHETIC_NEW_CREDENTIAL_ONLY',json.dumps(calls))
        self.assertEqual(helper.scope_fingerprint(self.scope),helper.scope_fingerprint(helper.scope_of(actual)))
    def test_replacement_hash_is_durable_before_bridge_stop(self):
        self.prepare();state=helper.read_json(self.statepath)
        expected=json.loads(json.dumps(self.compose))
        expected['services'][helper.SERVICE]['environment']['ARCHIVE_TOKEN']='SYNTHETIC_NEW_CREDENTIAL_ONLY'
        replacement=(json.dumps(expected,ensure_ascii=False,indent=2)+'\n').encode()
        def run(args):
            if args[:2]==['docker','stop']:
                durable=helper.read_json(self.statepath)
                self.assertEqual(helper.digest(replacement),durable.get('pending_compose_sha256'))
                self.assertEqual(state['original_compose_sha256'],helper.digest(self.composepath.read_bytes()))
            return 'true' if '--format' in args else ''
        with patch.object(helper,'container_identity',return_value=['fixture-id',helper.PROJECT,helper.SERVICE,state['bridge_image'],state['bridge_image_id']]),patch.object(helper,'run',side_effect=run):
            helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
    def test_failed_write_ahead_state_save_does_not_stop_or_replace_bridge(self):
        self.prepare();state=helper.read_json(self.statepath);before=self.composepath.read_bytes()
        with patch.object(helper,'container_identity',return_value=['fixture-id',helper.PROJECT,helper.SERVICE,state['bridge_image'],state['bridge_image_id']]),patch.object(helper,'run',return_value='true') as run,patch.object(helper,'save_state',side_effect=helper.FlowError('synthetic_disk_failure')):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(call.args[0][:2]==['docker','stop'] for call in run.call_args_list))
    def test_retry_after_replacement_before_state_commit_reuses_issued_receipt(self):
        self.prepare();state=helper.read_json(self.statepath);save=helper.save_state
        def interrupt(path,value):
            if value.get('phase')=='credential_installed_recreate_pending':
                raise helper.FlowError('synthetic_process_interruption')
            save(path,value)
        identity=['fixture-id',helper.PROJECT,helper.SERVICE,state['bridge_image'],state['bridge_image_id']]
        with patch.object(helper,'container_identity',return_value=identity),patch.object(helper,'run',return_value='true'),patch.object(helper,'save_state',side_effect=interrupt):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        issued={'state':'issued','scope_fingerprint':state['scope_fingerprint'],'credential':'SYNTHETIC_NEW_CREDENTIAL_ONLY'}
        with patch.object(helper,'container_identity',return_value=identity),patch.object(helper,'run',return_value='true'),patch.object(helper,'request',side_effect=[(200,issued),(200,{'state':'installed'})]) as request:
            try:
                result=helper.claim_and_install(self.statepath)
            except helper.FlowError:
                self.fail('durable write-ahead hash must allow the same issued receipt to finish installation')
        self.assertEqual('installed_waiting_for_natural_upload',result['phase'])
        self.assertEqual(2,request.call_count)
        self.assertNotIn('device_secret',helper.read_json(self.statepath))

class BridgePreflightTest(unittest.TestCase):
    def setUp(self):
        root=Path(os.environ.get('ARCHIVE_HELPER_TEST_ROOT', r'E:\Claude_allow\Download\matelink-fleet-recovery-20261029\helper-tests'))
        root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=root);self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        allowed=patch.object(helper,'ALLOWED',self.root);allowed.start();self.addCleanup(allowed.stop)
        acl=patch.object(helper,'protect');acl.start();self.addCleanup(acl.stop)
        self.scope={'vehicle_id':7,'source_instance_id':'fixture-collector','source_vehicle_id':'2'}
        self.image='fixture:bridge';self.image_id='sha256:'+'a'*64;self.tag_image_id=self.image_id
        self.container_id='fixture-container-id'
        self.container_present=True;self.container_project=helper.PROJECT
        self.service_conflicts=[];self.fail_recreate_after_remove=False;self.container_running=True
        self.environment={'ARCHIVE_VEHICLE_ID':'7','ARCHIVE_SOURCE_INSTANCE_ID':'fixture-collector',
                          'ARCHIVE_SOURCE_VEHICLE_ID':'2','ARCHIVE_URL':helper.API,'ARCHIVE_TOKEN':'SYNTHETIC_OLD_ONLY',
                          'DATABASE_URL':'postgres://fixture-user:FIXTURE_DB_VALUE@fixture/db','STATE_FILE':'/state/cursor.json'}
        self.live_environment=dict(self.environment)
        self.mounts=[{'Type':'volume','Name':'fixture_state','Source':'/var/lib/docker/volumes/fixture_state/_data',
                      'Destination':'/state','RW':True}]
        self.compose={'services':{helper.SERVICE:{'image':self.image,'environment':dict(self.environment),
                      'volumes':[{'type':'volume','source':'cursor','target':'/state'}]}},
                      'volumes':{'cursor':{'name':'fixture_state'}}}
        self.resolved=json.loads(json.dumps(self.compose))
        self.composepath=self.root/'compose.json';self.composepath.write_text(json.dumps(self.compose))
        self.statepath=self.root/'request'/'state.json'
        self.old_present=False;self.old_running=False;self.old_restart='no'
        self.old_inspect_failure=False;self.list_failure=False;self.commands=[]
        runner=patch.object(helper,'run',side_effect=self.docker);runner.start();self.addCleanup(runner.stop)

    def docker(self,args):
        self.commands.append(args)
        if args[:3]==['docker','container','ls']:
            if self.list_failure:raise helper.FlowError('fixture_docker_unavailable')
            if any(a.startswith('name=^/matelink-archive-bridge-before-safety-') for a in args):
                return 'matelink-archive-bridge-before-safety-20261015' if self.old_present else ''
            if 'name=^/'+helper.CONTAINER+'$' in args:return helper.CONTAINER if self.container_present else ''
            if 'label=com.docker.compose.project='+helper.PROJECT in args:
                names=([helper.CONTAINER] if self.container_present and self.container_project==helper.PROJECT else [])+self.service_conflicts
                return '\n'.join(names)
            self.fail('unexpected container inventory filter')
        if args[:3]==['docker','image','inspect']:
            return self.tag_image_id
        if args[:2]==['docker','compose']:
            if 'config' in args:return json.dumps(self.resolved)
            if 'up' in args:
                if self.fail_recreate_after_remove:
                    self.fail_recreate_after_remove=False;self.container_present=False
                    raise helper.FlowError('fixture_recreate_interrupted_after_remove')
                if not self.container_present:
                    self.container_present=True;self.container_id='recreated-fixture-container'
            return ''
        if args[:2]==['docker','stop']:return ''
        if args[:3]==['docker','inspect','--format']:
            template=args[3]
            if args[-1]=='matelink-archive-bridge-before-safety-20261015':
                if not self.old_present or self.old_inspect_failure:raise helper.FlowError('fixture_inspect_failed')
                running='true' if self.old_running else 'false'
                return running+'|'+self.old_restart if 'RestartPolicy' in template else running
            if not self.container_present:raise helper.FlowError('fixture_target_missing')
            if '.Id' in template:
                values=[self.container_id,self.container_project,helper.SERVICE,self.image]
                if '{{.Image}}' in template:values.append(self.image_id)
                return '|'.join(values)
            if '.Config.Env' in template:
                return '\n'.join(k+'='+v for k,v in self.live_environment.items() if k!='ARCHIVE_TOKEN')
            if '.Mounts' in template:return json.dumps(self.mounts)
            if '.State.Running' in template:return 'true' if self.container_running else 'false'
        self.fail('unexpected external operation in synthetic Docker fixture')

    def prepare(self):
        data={'handle':'a'*64,'device_secret':'b'*64,'verification_code':'123456','expires_at':'2099-01-01T00:00:00Z'}
        with patch.object(helper,'request',return_value=(201,data)):
            return helper.prepare(self.composepath,self.statepath)

    def assert_preflight_rejected(self):
        response={'handle':'a'*64,'device_secret':'b'*64,'verification_code':'123456','expires_at':'2099-01-01T00:00:00Z'}
        with patch.object(helper,'request',return_value=(201,response)) as request:
            with self.assertRaises(helper.FlowError):helper.prepare(self.composepath,self.statepath)
            request.assert_not_called()
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))

    def test_prepare_pins_image_content_without_persisting_environment_secrets(self):
        self.prepare();state=helper.read_json(self.statepath)
        self.assertEqual(self.image_id,state.get('bridge_image_id'))
        for value in ('SYNTHETIC_OLD_ONLY','FIXTURE_DB_VALUE',self.environment['DATABASE_URL']):
            self.assertNotIn(value,json.dumps(state));self.assertNotIn(value,json.dumps(self.commands))

    def test_prepare_persists_verified_resolved_runtime_digest(self):
        self.prepare();state=helper.read_json(self.statepath)
        self.assertRegex(state.get('bridge_runtime_sha256',''),r'^[0-9a-f]{64}$')
        self.assertNotIn(self.environment['DATABASE_URL'],json.dumps(state))

    def interrupted_recreate(self):
        self.prepare();state=helper.read_json(self.statepath)
        self.fail_recreate_after_remove=True
        with self.assertRaisesRegex(helper.FlowError,'^fixture_recreate_interrupted_after_remove$'):
            helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        durable=helper.read_json(self.statepath)
        self.assertEqual('credential_installed_recreate_pending',durable['phase'])
        self.assertEqual(durable['installed_compose_sha256'],helper.digest(self.composepath.read_bytes()))
        self.assertFalse(self.container_present);self.commands.clear()
        return durable

    def issued(self,state,credential='SYNTHETIC_NEW_CREDENTIAL_ONLY'):
        return 200,{'state':'issued','credential':credential,'scope_fingerprint':state['scope_fingerprint']}

    def assert_no_recreate_or_http(self):
        with patch.object(helper,'request') as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_recreate_missing_target_reclaims_same_receipt_then_verifies_before_ack(self):
        state=self.interrupted_recreate();installed=self.composepath.read_bytes()
        def server(path,payload):
            self.assertEqual({'device_secret':state['device_secret']},payload)
            if path.endswith('/claim'):return self.issued(state)
            self.assertTrue(path.endswith('/ack'));self.assertTrue(self.container_present)
            self.assertTrue(any(c[:2]==['docker','inspect'] and '.Config.Env' in c[3] for c in self.commands))
            return 200,{'state':'installed'}
        with patch.object(helper,'request',side_effect=server) as request:
            try:result=helper.claim_and_install(self.statepath)
            except helper.FlowError:self.fail('a verified missing container must resume its durable issued installation')
        self.assertEqual('installed_waiting_for_natural_upload',result['phase'])
        self.assertEqual(installed,self.composepath.read_bytes());self.assertEqual(2,request.call_count)
        self.assertNotIn('device_secret',helper.read_json(self.statepath))
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))

    def test_missing_target_with_server_installed_never_claims_local_success(self):
        state=self.interrupted_recreate()
        with patch.object(helper,'request',return_value=(200,{'state':'installed'})) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count)
        self.assertEqual(state,helper.read_json(self.statepath));self.assertFalse(self.container_present)
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_missing_target_without_runtime_fingerprint_is_not_recovered(self):
        state=self.interrupted_recreate();state.pop('bridge_runtime_sha256',None);helper.save_state(self.statepath,state)
        self.assert_no_recreate_or_http()

    def test_missing_target_outside_durable_recreate_phase_is_not_recovered(self):
        state=self.interrupted_recreate();state['phase']='credential_replacement_pending';helper.save_state(self.statepath,state)
        self.assert_no_recreate_or_http()

    def test_missing_target_with_only_original_hash_is_not_recovered(self):
        state=self.interrupted_recreate();state['installed_compose_sha256']=state['original_compose_sha256'];helper.save_state(self.statepath,state)
        self.assert_no_recreate_or_http()

    def test_missing_target_listing_failure_is_not_absence(self):
        self.interrupted_recreate();self.list_failure=True
        self.assert_no_recreate_or_http()

    def test_missing_target_rejects_another_container_with_same_project_service(self):
        self.interrupted_recreate();self.service_conflicts=['other-fixture-service-container']
        self.assert_no_recreate_or_http()

    def test_missing_recovery_rejects_conflicting_fixed_name(self):
        self.interrupted_recreate();self.container_present=True;self.container_project='other-fixture-project'
        self.assert_no_recreate_or_http()

    def test_missing_target_rejects_database_interpolation_drift(self):
        self.interrupted_recreate();self.resolved['services'][helper.SERVICE]['environment']['DATABASE_URL']='postgres://other-fixture/db'
        self.assert_no_recreate_or_http()

    def test_missing_target_rejects_state_file_interpolation_drift(self):
        self.interrupted_recreate();self.resolved['services'][helper.SERVICE]['environment']['STATE_FILE']='/state/other.json'
        self.assert_no_recreate_or_http()

    def test_missing_target_rejects_mount_or_image_drift(self):
        self.interrupted_recreate();self.resolved['volumes']['cursor']['name']='other-fixture-cursor'
        self.assert_no_recreate_or_http()

    def test_missing_target_rejects_retargeted_image_tag(self):
        self.interrupted_recreate();self.tag_image_id='sha256:'+'b'*64
        self.assert_no_recreate_or_http()

    def test_missing_recovery_rejects_different_reissued_credential_without_writing(self):
        state=self.interrupted_recreate();before=self.composepath.read_bytes()
        with patch.object(helper,'request',return_value=self.issued(state,'SYNTHETIC_DIFFERENT_CREDENTIAL_ONLY')) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count);self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_missing_recovery_rechecks_container_absence_after_claim(self):
        state=self.interrupted_recreate();before=self.composepath.read_bytes()
        def another_container(path,payload):
            self.container_present=True
            return self.issued(state)
        with patch.object(helper,'request',side_effect=another_container) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count);self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_missing_recovery_rechecks_compose_after_claim(self):
        state=self.interrupted_recreate();external=self.composepath.read_bytes()+b'\n '
        def external_write(path,payload):
            self.composepath.write_bytes(external)
            return self.issued(state)
        with patch.object(helper,'request',side_effect=external_write) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count);self.assertEqual(external,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_missing_recovery_rechecks_resolved_database_after_claim(self):
        state=self.interrupted_recreate()
        def interpolation_change(path,payload):
            self.resolved['services'][helper.SERVICE]['environment']['DATABASE_URL']='postgres://other-fixture/db'
            return self.issued(state)
        with patch.object(helper,'request',side_effect=interpolation_change) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count)
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_missing_recovery_does_not_ack_a_created_but_stopped_container(self):
        state=self.interrupted_recreate();self.container_running=False
        with patch.object(helper,'request',return_value=self.issued(state)) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count);self.assertTrue(self.container_present)
        self.assertEqual('credential_installed_recreate_pending',helper.read_json(self.statepath)['phase'])

    def installed_before_local_ack_commit(self):
        self.prepare()
        helper.install_credential(self.statepath,helper.read_json(self.statepath),'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        state=helper.read_json(self.statepath)
        self.assertEqual('credential_installed_recreate_pending',state['phase'])
        self.assertEqual(state['installed_compose_sha256'],helper.digest(self.composepath.read_bytes()))
        self.commands.clear()
        return state

    def test_server_installed_with_stopped_bridge_preserves_secret_and_durable_phase(self):
        state=self.installed_before_local_ack_commit();self.container_running=False
        with patch.object(helper,'request',return_value=(200,{'state':'installed'})) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(1,request.call_count);self.assertEqual(state,helper.read_json(self.statepath))
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_server_installed_requires_durable_install_phase(self):
        state=self.installed_before_local_ack_commit();state['phase']='pending';helper.save_state(self.statepath,state)
        with patch.object(helper,'request',return_value=(200,{'state':'installed'})):
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(state,helper.read_json(self.statepath))

    def test_server_installed_requires_saved_installed_hash(self):
        state=self.installed_before_local_ack_commit()
        state['original_compose_sha256']=state.pop('installed_compose_sha256');helper.save_state(self.statepath,state)
        with patch.object(helper,'request',return_value=(200,{'state':'installed'})):
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(state,helper.read_json(self.statepath))

    def test_server_installed_requires_exact_installed_hash_even_when_original_hash_matches(self):
        state=self.installed_before_local_ack_commit()
        state['original_compose_sha256']=state['installed_compose_sha256'];state['installed_compose_sha256']='0'*64
        helper.save_state(self.statepath,state)
        with patch.object(helper,'request',return_value=(200,{'state':'installed'})):
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(state,helper.read_json(self.statepath))

    def test_server_installed_with_verified_running_bridge_can_finish_lost_ack(self):
        state=self.installed_before_local_ack_commit()
        with patch.object(helper,'request',return_value=(200,{'state':'installed'})) as request:
            result=helper.claim_and_install(self.statepath)
        self.assertEqual({'phase':'installed_waiting_for_natural_upload'},result)
        self.assertEqual(1,request.call_count);self.assertNotIn('device_secret',helper.read_json(self.statepath))
        self.assertEqual(state['installed_compose_sha256'],helper.digest(self.composepath.read_bytes()))
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_server_installed_rechecks_running_after_claim_response(self):
        state=self.installed_before_local_ack_commit()
        def stop_during_claim(path,payload):
            self.container_running=False
            return 200,{'state':'installed'}
        with patch.object(helper,'request',side_effect=stop_during_claim):
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(state,helper.read_json(self.statepath))

    def test_server_installed_rechecks_compose_after_claim_response(self):
        state=self.installed_before_local_ack_commit();external=self.composepath.read_bytes()+b'\n '
        def change_during_claim(path,payload):
            self.composepath.write_bytes(external)
            return 200,{'state':'installed'}
        with patch.object(helper,'request',side_effect=change_during_claim):
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(state,helper.read_json(self.statepath));self.assertEqual(external,self.composepath.read_bytes())

    def test_server_installed_rechecks_runtime_scope_after_claim_response(self):
        state=self.installed_before_local_ack_commit()
        def change_during_claim(path,payload):
            self.live_environment['DATABASE_URL']='postgres://other-fixture/db'
            return 200,{'state':'installed'}
        with patch.object(helper,'request',side_effect=change_during_claim):
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
        self.assertEqual(state,helper.read_json(self.statepath))

    def test_prepare_rejects_different_resolved_database_without_http(self):
        self.resolved['services'][helper.SERVICE]['environment']['DATABASE_URL']='postgres://other-fixture/db'
        self.assert_preflight_rejected()

    def test_prepare_rejects_different_resolved_state_file_without_http(self):
        self.resolved['services'][helper.SERVICE]['environment']['STATE_FILE']='/state/other.json'
        self.assert_preflight_rejected()

    def test_prepare_rejects_removed_runtime_poll_setting_without_http(self):
        self.live_environment['POLL_INTERVAL']='2m'
        self.assert_preflight_rejected()

    def test_prepare_rejects_different_named_cursor_volume_without_http(self):
        self.resolved['volumes']['cursor']['name']='different_fixture_state'
        self.assert_preflight_rejected()

    def test_prepare_rejects_mount_permission_drift_without_http(self):
        self.resolved['services'][helper.SERVICE]['volumes'][0]['read_only']=True
        self.assert_preflight_rejected()

    def test_prepare_rejects_retargeted_local_image_tag_without_http(self):
        self.tag_image_id='sha256:'+'b'*64
        self.assert_preflight_rejected()

    def test_prepare_rejects_different_compose_image_reference_without_http(self):
        self.resolved['services'][helper.SERVICE]['image']='different:fixture'
        self.assert_preflight_rejected()

    def test_claim_rejects_runtime_database_drift_before_http_or_stop(self):
        self.prepare();self.live_environment['DATABASE_URL']='postgres://other-fixture/db'
        with patch.object(helper,'request',return_value=(202,{'state':'pending'})) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))

    def test_claim_rejects_resolved_database_drift_before_http_or_stop(self):
        self.prepare();self.resolved['services'][helper.SERVICE]['environment']['DATABASE_URL']='postgres://other-fixture/db'
        with patch.object(helper,'request',return_value=(202,{'state':'pending'})) as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))

    def test_claim_rejects_retargeted_image_tag_before_http(self):
        self.prepare();self.tag_image_id='sha256:'+'b'*64
        with patch.object(helper,'request') as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()

    def test_claim_rejects_replaced_image_even_when_tag_and_mounts_match(self):
        self.prepare();self.tag_image_id=self.image_id='sha256:'+'b'*64
        with patch.object(helper,'request') as request:
            with self.assertRaises(helper.FlowError):helper.claim_and_install(self.statepath)
            request.assert_not_called()

    def test_install_rejects_runtime_state_drift_before_stop(self):
        self.prepare();state=helper.read_json(self.statepath);before=self.composepath.read_bytes()
        self.live_environment['STATE_FILE']='/state/other.json'
        with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))

    def test_compose_change_after_preflight_is_not_overwritten_or_stopped(self):
        self.prepare();state=helper.read_json(self.statepath);save=helper.save_state
        external=self.composepath.read_bytes()+b'\n '
        def concurrent_write(path,value):
            save(path,value)
            if value.get('phase')=='credential_replacement_pending':self.composepath.write_bytes(external)
        with patch.object(helper,'save_state',side_effect=concurrent_write):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(external,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_compose_change_during_stop_is_not_overwritten_or_recreated(self):
        self.prepare();state=helper.read_json(self.statepath);external=self.composepath.read_bytes()+b'\n '
        def concurrent_write(args):
            result=self.docker(args)
            if args[:2]==['docker','stop']:self.composepath.write_bytes(external)
            return result
        with patch.object(helper,'run',side_effect=concurrent_write):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(external,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_compose_change_while_staging_private_replacement_is_not_overwritten(self):
        self.prepare();state=helper.read_json(self.statepath);external=self.composepath.read_bytes()+b'\n '
        write=helper.write_private;sync=helper.os.fsync
        def concurrent_write(path,data,**options):
            if Path(path)!=self.composepath:return write(path,data,**options)
            def changed_after_flush(descriptor):
                sync(descriptor);self.composepath.write_bytes(external)
            with patch.object(helper.os,'fsync',side_effect=changed_after_flush):return write(path,data,**options)
        with patch.object(helper,'write_private',side_effect=concurrent_write):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(external,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_container_replacement_after_preflight_does_not_stop_or_write(self):
        self.prepare();state=helper.read_json(self.statepath);before=self.composepath.read_bytes();save=helper.save_state
        def concurrent_recreate(path,value):
            save(path,value)
            if value.get('phase')=='credential_replacement_pending':self.container_id='another-fixture-container'
        with patch.object(helper,'save_state',side_effect=concurrent_recreate):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','stop'] for c in self.commands))
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_container_replacement_during_stop_does_not_write_or_recreate(self):
        self.prepare();state=helper.read_json(self.statepath);before=self.composepath.read_bytes()
        def concurrent_recreate(args):
            result=self.docker(args)
            if args[:2]==['docker','stop']:self.container_id='another-fixture-container'
            return result
        with patch.object(helper,'run',side_effect=concurrent_recreate):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_container_replacement_while_staging_does_not_overwrite_compose(self):
        self.prepare();state=helper.read_json(self.statepath);before=self.composepath.read_bytes()
        write=helper.write_private;sync=helper.os.fsync
        def concurrent_recreate(path,data,**options):
            if Path(path)!=self.composepath:return write(path,data,**options)
            def changed_after_flush(descriptor):
                sync(descriptor);self.container_id='another-fixture-container'
            with patch.object(helper.os,'fsync',side_effect=changed_after_flush):return write(path,data,**options)
        with patch.object(helper,'write_private',side_effect=concurrent_recreate):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(before,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_external_compose_change_after_replace_never_reaches_compose_up(self):
        self.prepare();state=helper.read_json(self.statepath);save=helper.save_state
        external=self.composepath.read_bytes()+b'\n '
        def concurrent_write(path,value):
            save(path,value)
            if value.get('phase')=='credential_installed_recreate_pending':self.composepath.write_bytes(external)
        with patch.object(helper,'save_state',side_effect=concurrent_write):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertEqual(external,self.composepath.read_bytes())
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_container_replacement_after_replace_never_reaches_compose_up(self):
        self.prepare();state=helper.read_json(self.statepath);save=helper.save_state
        def concurrent_recreate(path,value):
            save(path,value)
            if value.get('phase')=='credential_installed_recreate_pending':self.container_id='another-fixture-container'
        with patch.object(helper,'save_state',side_effect=concurrent_recreate):
            with self.assertRaises(helper.FlowError):helper.install_credential(self.statepath,state,'SYNTHETIC_NEW_CREDENTIAL_ONLY')
        self.assertFalse(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))

    def test_stopped_duplicate_with_restart_policy_is_rejected(self):
        self.old_present=True;self.old_restart='always'
        self.assert_preflight_rejected()

    def test_running_duplicate_is_rejected(self):
        self.old_present=True;self.old_running=True
        self.assert_preflight_rejected()

    def test_duplicate_inspect_failure_is_not_treated_as_absent(self):
        self.old_present=True;self.old_inspect_failure=True
        self.assert_preflight_rejected()

    def test_duplicate_listing_failure_is_not_treated_as_absent(self):
        self.list_failure=True
        self.assert_preflight_rejected()

    def test_absent_duplicate_after_successful_listing_is_safe(self):
        self.prepare()
        self.assertTrue(self.statepath.exists())

    def test_stopped_duplicate_with_no_restart_is_safe(self):
        self.old_present=True
        self.prepare()
        self.assertTrue(self.statepath.exists())

    def test_windows_bind_cursor_maps_to_docker_desktop_host_path(self):
        self.resolved['services'][helper.SERVICE]['volumes']=[{'type':'bind','source':r'E:\fixture\cursor','target':'/state'}]
        self.mounts=[{'Type':'bind','Source':'/run/desktop/mnt/host/e/fixture/cursor','Destination':'/state','RW':True}]
        self.prepare()
        self.assertTrue(self.statepath.exists())

    def test_different_bind_cursor_path_is_rejected(self):
        self.resolved['services'][helper.SERVICE]['volumes']=[{'type':'bind','source':r'E:\fixture\other','target':'/state'}]
        self.mounts=[{'Type':'bind','Source':'/run/desktop/mnt/host/e/fixture/cursor','Destination':'/state','RW':True}]
        self.assert_preflight_rejected()

    def test_full_cli_install_changes_only_credential_and_acknowledges_after_recreate(self):
        self.prepare();original=helper.read_json(self.composepath);output=io.StringIO()
        state=helper.read_json(self.statepath)
        def server(path,payload):
            self.assertEqual({'device_secret':state['device_secret']},payload)
            if path.endswith('/claim'):
                return 200,{'state':'issued','scope_fingerprint':state['scope_fingerprint'],
                            'credential':'SYNTHETIC_NEW_CREDENTIAL_ONLY'}
            self.assertTrue(path.endswith('/ack'))
            self.assertTrue(any(c[:2]==['docker','compose'] and 'up' in c for c in self.commands))
            self.assertEqual('SYNTHETIC_NEW_CREDENTIAL_ONLY',helper.read_json(self.composepath)['services'][helper.SERVICE]['environment']['ARCHIVE_TOKEN'])
            return 200,{'state':'installed'}
        with patch.object(helper,'request',side_effect=server),contextlib.redirect_stdout(output):
            code=helper.main(['claim-install','--state',str(self.statepath)])
        self.assertEqual(0,code)
        self.assertEqual({'phase':'installed_waiting_for_natural_upload'},json.loads(output.getvalue()))
        original['services'][helper.SERVICE]['environment']['ARCHIVE_TOKEN']='SYNTHETIC_NEW_CREDENTIAL_ONLY'
        self.assertEqual(original,helper.read_json(self.composepath))
        self.assertNotIn('device_secret',helper.read_json(self.statepath))
        for value in (state['device_secret'],'SYNTHETIC_NEW_CREDENTIAL_ONLY','SYNTHETIC_OLD_ONLY',self.environment['DATABASE_URL']):
            self.assertNotIn(value,output.getvalue());self.assertNotIn(value,json.dumps(self.commands))

if __name__=='__main__':unittest.main()
