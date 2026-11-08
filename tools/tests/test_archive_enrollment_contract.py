"""Offline comparison contract pinned to local backend ab9a245c source.

archive_enrollment.go:69-74 uses the compact JSON array and SHA256 prefix;
preview:336 and issued response:513 share that value. The App accepts a
64-character lower-case hex handle and a six-digit ASCII comparison code.
No assertion here claims that the current production deployment was queried.
"""
import copy
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    'archive_comparison_helper', Path(__file__).parents[1] / 'archive_enrollment_helper.py')
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


class WireResponse:
    status = 201

    def __init__(self, data):
        self.body = json.dumps({'data': data}).encode()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, size):
        return self.body[:size]


class WireOpener:
    def __init__(self, data):
        self.data = data
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        return WireResponse(self.data)


class PublicComparisonContractTest(unittest.TestCase):
    def setUp(self):
        runs = Path(os.environ.get('ARCHIVE_HELPER_TEST_ROOT',
                    r'E:\Claude_allow\Download\matelink-fleet-recovery-20261029\helper-tests'))
        runs.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=runs)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scope = {'vehicle_id': 1, 'source_instance_id': 'home-instance', 'source_vehicle_id': 'car-1'}
        self.expected = '51b2157c3cc0d87a'
        self.wire = {'handle': 'ab' * 32, 'device_secret': 'cd' * 32,
                     'verification_code': '001234', 'expires_at': '2099-01-01T00:00:00Z',
                     'interval_seconds': 5}
        self.compose = {'services': {helper.SERVICE: {'environment': {
            'ARCHIVE_VEHICLE_ID': '1', 'ARCHIVE_SOURCE_INSTANCE_ID': 'home-instance',
            'ARCHIVE_SOURCE_VEHICLE_ID': 'car-1', 'ARCHIVE_URL': helper.API,
            'ARCHIVE_TOKEN': 'OFFLINE_OLD_CREDENTIAL'}}}}
        self.compose_path = self.root / 'compose.json'
        self.compose_path.write_text(json.dumps(self.compose))
        self.state_path = self.root / 'private' / 'request.json'
        for patcher in (
            patch.object(helper, 'ALLOWED', self.root),
            patch.object(helper, 'protect'),
            patch.object(helper, 'bridge_snapshot', return_value={
                'image': 'offline:bridge', 'image_id': 'sha256:' + 'a' * 64,
                'mounts_sha256': 'b' * 64, 'runtime_sha256': 'c' * 64}),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def prepare_wire(self, data=None):
        opener = WireOpener(self.wire if data is None else data)
        with patch.object(helper.urllib.request, 'build_opener', return_value=opener):
            summary = helper.prepare(self.compose_path, self.state_path)
        return summary, opener

    def state(self):
        self.prepare_wire()
        return helper.read_json(self.state_path)

    def test_fixed_backend_vector_matches_existing_claim_and_preview_fingerprint(self):
        self.assertEqual(self.expected, helper.scope_fingerprint(self.scope))

    def test_real_wire_preserves_leading_zero_and_displays_the_verified_scope(self):
        summary, opener = self.prepare_wire()
        self.assertEqual(self.expected, summary.get('scope_fingerprint'))
        self.assertEqual('001234', summary['verification_code'])
        self.assertEqual(helper.AUTH + '/archive/enroll?handle=' + 'ab' * 32, summary['app_link'])
        self.assertEqual(self.scope, json.loads(opener.requests[0].data))
        self.assertEqual({'Content-type': 'application/json', 'Accept': 'application/json'},
                         dict(opener.requests[0].header_items()))

    def test_summary_exposes_only_public_comparison_fields(self):
        summary, _ = self.prepare_wire()
        self.assertEqual({'phase', 'verification_code', 'expires_at', 'app_link', 'scope_fingerprint'}, set(summary))
        encoded = json.dumps(summary)
        for private in (self.wire['device_secret'], 'OFFLINE_OLD_CREDENTIAL', 'home-instance', 'car-1'):
            self.assertNotIn(private, encoded)

    def test_displayed_contract_fingerprint_is_accepted_by_existing_claim_validation(self):
        summary, _ = self.prepare_wire()
        issued = {'state': 'issued', 'scope_fingerprint': self.expected,
                  'credential': 'OFFLINE_NEW_CREDENTIAL_ONLY'}
        with patch.object(helper, 'verify_bridge_snapshot'), \
                patch.object(helper, 'install_credential') as install, \
                patch.object(helper, 'request', side_effect=[(200, issued), (200, {'state': 'installed'})]) as request:
            result = helper.claim_and_install(self.state_path)
        self.assertEqual(summary['scope_fingerprint'], issued['scope_fingerprint'])
        install.assert_called_once()
        self.assertEqual('OFFLINE_NEW_CREDENTIAL_ONLY', install.call_args.args[2])
        self.assertTrue(request.call_args_list[0].args[0].endswith('/claim'))
        self.assertTrue(request.call_args_list[1].args[0].endswith('/ack'))
        self.assertEqual({'phase': 'installed_waiting_for_natural_upload'}, result)
        self.assertNotIn('device_secret', helper.read_json(self.state_path))

    def test_legacy_missing_and_malformed_fingerprints_fail_closed(self):
        original = self.state()
        for value in (None, '', 'a' * 15, 'a' * 17, 'A' * 16, 'g' * 16, 1, [], {}, '0' * 16):
            with self.subTest(value=value):
                state = copy.deepcopy(original)
                state['scope_fingerprint'] = value
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)
        original.pop('scope_fingerprint')
        with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
            helper.public_summary(original)

    def test_changed_scope_cannot_reuse_a_previously_verified_fingerprint(self):
        state = self.state()
        for key, value in (('vehicle_id', 2), ('source_instance_id', 'another-instance'),
                           ('source_vehicle_id', 'another-car')):
            with self.subTest(key=key):
                changed = copy.deepcopy(state)
                changed['scope'][key] = value
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(changed)

    def test_missing_or_malformed_scope_fails_closed(self):
        original = self.state()
        for scope in (None, [], {}, {'vehicle_id': 1}):
            with self.subTest(scope=scope):
                state = copy.deepcopy(original)
                state['scope'] = scope
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)

    def test_unverified_state_cannot_launch_a_phone_confirmation(self):
        state = self.state()
        state.pop('scope_fingerprint')
        helper.save_state(self.state_path, state)
        with patch.object(helper, 'run', return_value='offline-phone device') as runner:
            with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                helper.open_app(self.state_path, 'offline-adb')
        self.assertFalse(any('start' in command for call in runner.call_args_list for command in call.args))

    def test_noncanonical_wire_handles_are_rejected_before_state_is_saved(self):
        for index, handle in enumerate(('a' * 63, 'a' * 65, 'A' * 64, 'g' * 64, 'H' * 24, 'a' * 64 + '?x=1',
                                        None, 1, [], {})):
            with self.subTest(handle=handle):
                self.state_path = self.root / 'private' / ('handle-' + str(index) + '.json')
                data = dict(self.wire, handle=handle)
                with self.assertRaisesRegex(helper.FlowError, '^enrollment_proof_invalid$'):
                    self.prepare_wire(data)
                self.assertFalse(self.state_path.exists())

    def test_noncanonical_wire_codes_are_rejected_before_state_is_saved(self):
        for index, code in enumerate(('12345', '1234567', '1234-5678', '123 456', '１２３４５６', '١٢٣٤٥٦',
                                      None, 123456, [], {})):
            with self.subTest(code=code):
                self.state_path = self.root / 'private' / ('code-' + str(index) + '.json')
                data = dict(self.wire, verification_code=code)
                with self.assertRaisesRegex(helper.FlowError, '^verification_code_invalid$'):
                    self.prepare_wire(data)
                self.assertFalse(self.state_path.exists())

    def test_rehashed_invalid_vehicle_types_and_bounds_cannot_be_displayed(self):
        original = self.state()
        for value in (True, False, '1', 1.0, 0, -1, 2147483648, None, [], {}):
            with self.subTest(value=value):
                state = copy.deepcopy(original)
                state['scope']['vehicle_id'] = value
                state['scope_fingerprint'] = helper.scope_fingerprint(state['scope'])
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)

    def test_rehashed_invalid_source_types_and_shapes_cannot_be_displayed(self):
        original = self.state()
        for field in ('source_instance_id', 'source_vehicle_id'):
            for value in (None, True, 1, [], {'private': self.wire['device_secret']},
                          '', 'a' * 129, 'source id', 'source/id', '来源'):
                with self.subTest(field=field, value=value):
                    state = copy.deepcopy(original)
                    state['scope'][field] = value
                    state['scope_fingerprint'] = helper.scope_fingerprint(state['scope'])
                    with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                        helper.public_summary(state)

    def test_reloaded_handle_must_keep_the_canonical_app_link_shape(self):
        original = self.state()
        for value in (None, True, 1, [], {}, 'a' * 63, 'a' * 65, 'A' * 64, 'g' * 64,
                      'a' * 64 + '?token=private', 'a' * 64 + '\n'):
            with self.subTest(value=value):
                state = dict(original, handle=value)
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)

    def test_reloaded_code_cannot_bypass_prepare_or_expose_an_object(self):
        original = self.state()
        for value in (None, True, 123456, [], {'private': self.wire['device_secret']},
                      '12345', '1234567', '1234-5678', '１２３４５６', '001234\n'):
            with self.subTest(value=value):
                state = dict(original, verification_code=value)
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)

    def test_reloaded_expiry_must_be_a_valid_backend_utc_timestamp(self):
        original = self.state()
        for value in (None, True, 1, [], {'private': self.wire['device_secret']}, '',
                      '2099-01-01', '2099-01-01T00:00:00', '2099-01-01T00:00:00+00:00',
                      '2099-02-30T00:00:00Z', '2099-13-01T00:00:00Z',
                      '2099-01-01T24:00:00Z', '2099-01-01T00:00:60Z',
                      '2099-01-01T00:00:00.1234567890Z', '2099-01-01T00:00:00Z\n'):
            with self.subTest(value=value):
                state = dict(original, expires_at=value)
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)

    def test_missing_comparison_fields_and_non_object_state_fail_with_fixed_error(self):
        original = self.state()
        for field in ('phase', 'handle', 'verification_code', 'expires_at', 'scope', 'scope_fingerprint'):
            with self.subTest(field=field):
                state = dict(original)
                state.pop(field)
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)
        for state in (None, [], 'private', 1):
            with self.subTest(state=state):
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(state)

    def test_phase_cannot_carry_private_or_unrecognized_content(self):
        original = self.state()
        for value in (None, [], {'token': self.wire['device_secret']}, 'approved',
                      'pending\n' + self.scope['source_instance_id']):
            with self.subTest(value=value):
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    helper.public_summary(dict(original, phase=value))

    def test_corrupt_state_cli_never_launches_or_echoes_private_values(self):
        state = self.state()
        state['verification_code'] = {'token': self.wire['device_secret'],
                                      'source': self.scope['source_instance_id']}
        helper.save_state(self.state_path, state)
        output = io.StringIO()
        with patch.object(helper, 'run', return_value='offline-phone device') as runner, \
                contextlib.redirect_stdout(output):
            code = helper.main(['open-app', '--state', str(self.state_path), '--adb', 'offline-adb'])
        self.assertEqual({'phase': 'error', 'code': 'comparison_scope_unverifiable'}, json.loads(output.getvalue()))
        self.assertEqual(1, code)
        for private in (self.wire['device_secret'], self.scope['source_instance_id'], 'OFFLINE_OLD_CREDENTIAL'):
            self.assertNotIn(private, output.getvalue())
        self.assertFalse(any('start' in command for call in runner.call_args_list for command in call.args))

    def test_invalid_wire_expiry_is_rejected_without_persisting_a_partial_state(self):
        for index, value in enumerate((None, {}, [], '2099-02-30T00:00:00Z', 'not-a-date')):
            with self.subTest(value=value):
                self.state_path = self.root / 'private' / ('expiry-' + str(index) + '.json')
                with self.assertRaisesRegex(helper.FlowError, '^comparison_scope_unverifiable$'):
                    self.prepare_wire(dict(self.wire, expires_at=value))
                self.assertFalse(self.state_path.exists())

    def test_valid_historical_comparison_states_keep_original_phases_and_precision(self):
        original = self.state()
        for phase in ('pending', 'credential_replacement_pending',
                      'credential_installed_recreate_pending', 'installed_waiting_for_natural_upload'):
            for expiry in ('2099-01-01T00:00:00Z', '2099-01-01T00:00:00.1Z',
                           '2099-01-01T00:00:00.123456789Z', '2024-02-29T23:59:59Z'):
                with self.subTest(phase=phase, expiry=expiry):
                    state = dict(original, phase=phase, expires_at=expiry)
                    if phase == 'installed_waiting_for_natural_upload':
                        state.pop('device_secret')
                    summary = helper.public_summary(state)
                    self.assertEqual(phase, summary['phase'])
                    self.assertEqual(expiry, summary['expires_at'])
                    self.assertEqual('001234', summary['verification_code'])
                    self.assertEqual(self.expected, summary['scope_fingerprint'])
        for vehicle, source in ((2147483647, 'a' * 128), (1, 'a._:-0')):
            with self.subTest(vehicle=vehicle, source=source):
                state = copy.deepcopy(original)
                state['scope'].update(vehicle_id=vehicle, source_instance_id=source, source_vehicle_id=source)
                state['scope_fingerprint'] = helper.scope_fingerprint(state['scope'])
                self.assertEqual(state['scope_fingerprint'], helper.public_summary(state)['scope_fingerprint'])


if __name__ == '__main__':
    unittest.main()
