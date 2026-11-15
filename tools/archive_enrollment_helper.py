"""Same-scope archive enrollment. Never prints device secrets or credentials.

Concurrent-change checks around stop, atomic replace and recreate detect changes
observed at those boundaries. They are not a filesystem/Docker compare-and-swap:
external writers can still race between the last check and the operation. Other
helpers and deployment automation must be coordinated outside this process.
"""
import argparse
import csv
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

API = 'https://' + '.'.join(('api', 'teslalink', 'joviluma', 'com'))
AUTH = 'https://' + '.'.join(('auth', 'teslalink', 'joviluma', 'com'))
ALLOWED = Path(r'E:\Claude_allow\Download')
SERVICE = 'matelink-archive-bridge'
PROJECT = 'matelink-archive-production'
CONTAINER = 'teslamate-home-docker-matelink-archive-bridge-1'

class FlowError(Exception):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise FlowError('redirect_rejected')

def digest(data):
    return hashlib.sha256(data).hexdigest()

def bounded_path(value):
    path = Path(value).resolve()
    try:
        path.relative_to(ALLOWED.resolve())
    except ValueError:
        raise FlowError('path_outside_approved_directory')
    return path

def run(command):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if result.returncode:
        raise FlowError('local_operation_failed')
    return result.stdout.decode('utf-8', errors='replace').strip()

def protect(path, directory=False):
    if os.name != 'nt':
        os.chmod(path, 0o700 if directory else 0o600)
        return
    identity = run(['whoami', '/user', '/fo', 'csv', '/nh'])
    fields = next(csv.reader(io.StringIO(identity)))
    sid = fields[-1]
    if not re.fullmatch(r'S-1-[0-9-]+', sid):
        raise FlowError('local_identity_unavailable')
    permission = '(OI)(CI)(F)' if directory else '(F)'
    run(['icacls', str(path), '/inheritance:r', '/grant:r', '*' + sid + ':' + permission,
         '*S-1-5-18:' + permission, '/remove:g', '*S-1-1-0', '*S-1-5-11', '*S-1-5-32-545'])

def write_private(path, data, before_replace=None):
    path = bounded_path(path)
    if not path.parent.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        protect(path.parent, True)
    # A hard interruption can leave a protected temporary behind. Each retry
    # owns a fresh exclusive file and never trusts, overwrites or deletes a
    # previous process's incomplete contents.
    temp = path.with_name(path.name + '.' + os.urandom(16).hex() + '.new')
    descriptor = os.open(temp, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        protect(temp)
        with os.fdopen(descriptor, 'wb') as target:
            target.write(data)
            target.flush()
            os.fsync(target.fileno())
        if before_replace is not None:
            before_replace()
        os.replace(temp, path)
    except Exception:
        try:
            os.close(descriptor)
        except OSError:
            pass
        try:
            temp.unlink()
        except OSError:
            pass
        raise

def save_state(path, value):
    write_private(path, (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())

def read_json(path):
    path = bounded_path(path)
    if not path.is_file() or path.stat().st_size > 65536:
        raise FlowError('private_input_invalid')
    return json.loads(path.read_text(encoding='utf-8-sig'))

def scope_of(compose):
    try:
        env = compose['services'][SERVICE]['environment']
        if not isinstance(env, dict):
            raise ValueError()
        scope = {'vehicle_id': int(env['ARCHIVE_VEHICLE_ID']),
                 'source_instance_id': str(env['ARCHIVE_SOURCE_INSTANCE_ID']),
                 'source_vehicle_id': str(env['ARCHIVE_SOURCE_VEHICLE_ID'])}
        if scope['vehicle_id'] <= 0:
            raise ValueError()
        for key in ('source_instance_id', 'source_vehicle_id'):
            if not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', scope[key]):
                raise ValueError()
        # The bridge appends the import path itself; only this exact HTTPS
        # origin is an installation target, including no empty query suffix.
        if env['ARCHIVE_URL'] != API:
            raise ValueError()
        return scope
    except (KeyError, TypeError, ValueError):
        raise FlowError('original_scope_invalid')

def scope_fingerprint(scope):
    encoded = json.dumps([scope['vehicle_id'], scope['source_instance_id'], scope['source_vehicle_id']],
                         ensure_ascii=False, separators=(',', ':')).encode()
    return digest(encoded)[:16]

def request(path, payload, opener=None):
    if not re.fullmatch(r'/api/v1/archive/enrollments(?:/[A-Za-z0-9_-]{20,128}/(?:claim|ack))?', path):
        raise FlowError('request_path_invalid')
    body = json.dumps(payload, separators=(',', ':')).encode()
    if len(body) > 4096:
        raise FlowError('request_too_large')
    req = urllib.request.Request(API + path, data=body,
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json'},
                                 method='POST')
    opener = opener or urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=15) as response:
            data = response.read(65537)
            if len(data) > 65536:
                raise FlowError('response_too_large')
            envelope = json.loads(data)
            if not isinstance(envelope, dict) or not isinstance(envelope.get('data'), dict):
                raise FlowError('response_invalid')
            return response.status, envelope['data']
    except urllib.error.HTTPError as error:
        classes = {400: 'request_rejected', 401: 'proof_rejected', 403: 'scope_or_approval_rejected',
                   404: 'endpoint_or_request_missing', 410: 'request_expired', 429: 'poll_limited'}
        raise FlowError(classes.get(error.code, 'server_unavailable'))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        raise FlowError('network_or_response_unavailable')

def canonical_bind_source(source):
    source = source.replace('\\', '/')
    for prefix in ('/run/desktop/mnt/host/', '/host_mnt/'):
        if source.startswith(prefix) and re.match(r'^[A-Za-z]/', source[len(prefix):]):
            source = source[len(prefix)] + ':' + source[len(prefix) + 1:]
            break
    if re.match(r'^[A-Za-z]:/', source):
        source = source.casefold()
    return source.rstrip('/') or '/'

def expected_mount_shape(resolved):
    expected = []
    for mount in resolved['services'][SERVICE].get('volumes', []):
        kind, source, target = mount['type'], mount.get('source'), mount['target']
        if kind == 'volume':
            source = resolved['volumes'][source]['name']
        elif kind == 'bind':
            source = canonical_bind_source(source)
        else:
            raise FlowError('production_mount_unverifiable')
        if not isinstance(source, str) or not source or not isinstance(target, str) or not target.startswith('/'):
            raise FlowError('production_mount_unverifiable')
        expected.append((kind, source, target, not mount.get('read_only', False)))
    if not expected:
        raise FlowError('production_mount_unverifiable')
    return sorted(expected)

def mount_shapes(resolved, mounts):
    expected, actual = expected_mount_shape(resolved), []
    for mount in mounts:
        kind, source = mount['Type'], mount.get('Name')
        if kind == 'bind':
            source = canonical_bind_source(mount['Source'])
        elif kind != 'volume' or not source:
            raise FlowError('production_mount_unverifiable')
        actual.append((kind, source, mount['Destination'], bool(mount['RW'])))
    if expected != sorted(actual):
        raise FlowError('production_mounts_changed')
    return sorted(actual)

def resolved_bridge_snapshot(scope, compose_path):
    # Only the one-way fingerprint is persisted; resolved environment values
    # (including DATABASE_URL) are never put in request state or output.
    resolved = json.loads(run(['docker', 'compose', '-p', PROJECT, '-f', str(bounded_path(compose_path)),
                               'config', '--format', 'json']))
    try:
        service = resolved['services'][SERVICE]
        environment = service['environment']
        required = {'DATABASE_URL', 'STATE_FILE', 'ARCHIVE_URL', 'ARCHIVE_VEHICLE_ID',
                    'ARCHIVE_SOURCE_INSTANCE_ID', 'ARCHIVE_SOURCE_VEHICLE_ID'}
        if scope_of(resolved) != scope or not isinstance(environment, dict) or not required.issubset(environment):
            raise FlowError('production_environment_unverifiable')
        if not all(isinstance(environment[key], str) and environment[key] for key in required):
            raise FlowError('production_environment_unverifiable')
        image = service['image']
        image_id = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', image])
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
            raise FlowError('production_bridge_image_changed')
        shape = expected_mount_shape(resolved)
        runtime = dict(service)
        runtime['environment'] = {key: value for key, value in environment.items() if key != 'ARCHIVE_TOKEN'}
        runtime['volumes'] = shape
        runtime['verified_image_id'] = image_id
        runtime['volume_definitions'] = {mount['source']: resolved['volumes'][mount['source']]
                                         for mount in service.get('volumes', []) if mount['type'] == 'volume'}
        fingerprint = digest(json.dumps(runtime, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode())
    except (KeyError, TypeError, ValueError, AttributeError):
        raise FlowError('production_configuration_unverifiable')
    return resolved, {'image': image, 'image_id': image_id, 'runtime_sha256': fingerprint,
                      'mounts_sha256': digest(json.dumps(shape, separators=(',', ':')).encode())}

def verify_duplicate_stopped():
    old = 'matelink-archive-bridge-before-safety-20261015'
    names = run(['docker', 'container', 'ls', '--all', '--filter', 'name=^/' + old + '$',
                 '--format', '{{.Names}}']).splitlines()
    if not names:
        return
    if names != [old]:
        raise FlowError('duplicate_bridge_unverifiable')
    # A successful listing is required to prove absence. Inspect/daemon failures
    # are never reclassified as an absent container.
    state = run(['docker', 'inspect', '--format',
                 '{{.State.Running}}|{{.HostConfig.RestartPolicy.Name}}', old])
    if state != 'false|no':
        raise FlowError('duplicate_bridge_not_disabled')

def bridge_snapshot(scope, compose_path):
    identity = container_identity()
    # Values including DATABASE_URL are captured only in this process for a
    # blind comparison. Do not read the old upload token or return these values.
    template = ('{{range .Config.Env}}{{ $p := split . "=" }}'
                '{{if ne (index $p 0) "ARCHIVE_TOKEN"}}{{println .}}{{end}}{{end}}')
    values = dict(line.split('=', 1) for line in run(['docker', 'inspect', '--format', template, CONTAINER]).splitlines() if '=' in line)
    try:
        live = {'vehicle_id': int(values['ARCHIVE_VEHICLE_ID']),
                'source_instance_id': values['ARCHIVE_SOURCE_INSTANCE_ID'],
                'source_vehicle_id': values['ARCHIVE_SOURCE_VEHICLE_ID']}
    except (KeyError, ValueError):
        raise FlowError('production_scope_unverifiable')
    if live != scope:
        raise FlowError('production_scope_changed')
    mounts = json.loads(run(['docker', 'inspect', '--format', '{{json .Mounts}}', CONTAINER]))
    resolved, snapshot = resolved_bridge_snapshot(scope, compose_path)
    try:
        service = resolved['services'][SERVICE]
        environment = service['environment']
        required = {'DATABASE_URL', 'STATE_FILE', 'ARCHIVE_URL', 'ARCHIVE_VEHICLE_ID',
                    'ARCHIVE_SOURCE_INSTANCE_ID', 'ARCHIVE_SOURCE_VEHICLE_ID'}
        if scope_of(resolved) != scope or not isinstance(environment, dict) or not required.issubset(environment):
            raise FlowError('production_environment_unverifiable')
        tracked = (set(environment) | required | {'BATCH_SIZE', 'POLL_INTERVAL'}) - {'ARCHIVE_TOKEN'}
        if any(environment.get(key) != values.get(key) for key in tracked):
            raise FlowError('production_environment_changed')
        if not environment['DATABASE_URL'] or not environment['STATE_FILE'] or service['image'] != identity[3]:
            raise FlowError('production_configuration_changed')
        if snapshot['image_id'] != identity[4]:
            raise FlowError('production_bridge_image_changed')
        mount_shapes(resolved, mounts)
    except (KeyError, TypeError, ValueError, AttributeError):
        raise FlowError('production_configuration_unverifiable')
    verify_duplicate_stopped()
    return snapshot

def verify_bridge_snapshot(state):
    if not re.fullmatch(r'[0-9a-f]{64}', state.get('bridge_runtime_sha256', '')):
        raise FlowError('production_runtime_unverifiable')
    current = bridge_snapshot(state['scope'], state['compose_path'])
    if (current['image'] != state['bridge_image'] or current['image_id'] != state['bridge_image_id'] or
            current['mounts_sha256'] != state['bridge_mounts_sha256'] or
            current['runtime_sha256'] != state['bridge_runtime_sha256']):
        raise FlowError('production_bridge_changed')

def verify_bridge_absent():
    # Neither an inspect error nor a daemon failure proves absence. Both
    # successful inventories must be empty, including renamed service instances.
    by_name = run(['docker', 'container', 'ls', '--all', '--filter', 'name=^/' + CONTAINER + '$',
                   '--format', '{{.Names}}'])
    by_service = run(['docker', 'container', 'ls', '--all',
                      '--filter', 'label=com.docker.compose.project=' + PROJECT,
                      '--filter', 'label=com.docker.compose.service=' + SERVICE,
                      '--format', '{{.Names}}'])
    if by_name or by_service:
        raise FlowError('production_bridge_conflict')

def verify_missing_recreate_target(state):
    if (state.get('phase') != 'credential_installed_recreate_pending' or
            not re.fullmatch(r'[0-9a-f]{64}', state.get('bridge_runtime_sha256', '')) or
            not re.fullmatch(r'[0-9a-f]{64}', state.get('installed_compose_sha256', ''))):
        raise FlowError('missing_bridge_recovery_not_permitted')
    compose_path = bounded_path(state['compose_path'])
    if digest(compose_path.read_bytes()) != state['installed_compose_sha256']:
        raise FlowError('production_compose_changed')
    if scope_of(read_json(compose_path)) != state['scope'] or scope_fingerprint(state['scope']) != state['scope_fingerprint']:
        raise FlowError('production_scope_changed')
    verify_bridge_absent()
    _, current = resolved_bridge_snapshot(state['scope'], compose_path)
    if (current['image'] != state['bridge_image'] or current['image_id'] != state['bridge_image_id'] or
            current['mounts_sha256'] != state['bridge_mounts_sha256'] or
            current['runtime_sha256'] != state['bridge_runtime_sha256']):
        raise FlowError('production_bridge_changed')
    verify_duplicate_stopped()
    verify_bridge_absent()
    if digest(compose_path.read_bytes()) != state['installed_compose_sha256']:
        raise FlowError('production_compose_changed')

def prepare(compose_path, state_path):
    compose_path = bounded_path(compose_path)
    state_path = bounded_path(state_path)
    if state_path.exists():
        raise FlowError('request_state_already_exists')
    compose = read_json(compose_path)
    scope = scope_of(compose)
    bridge = bridge_snapshot(scope, compose_path)
    _, data = request('/api/v1/archive/enrollments', scope)
    handle = data.get('handle', '')
    secret = data.get('device_secret', '')
    code = data.get('verification_code', '')
    if (not isinstance(handle, str) or not re.fullmatch(r'[0-9a-f]{64}', handle) or
            not isinstance(secret, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', secret)):
        raise FlowError('enrollment_proof_invalid')
    if not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code):
        raise FlowError('verification_code_invalid')
    state = {'handle': handle, 'device_secret': secret, 'verification_code': code,
             'expires_at': data.get('expires_at'), 'scope': scope,
             'scope_fingerprint': scope_fingerprint(scope), 'compose_path': str(compose_path),
             'original_compose_sha256': digest(compose_path.read_bytes()), 'bridge_image': bridge['image'],
             'bridge_image_id': bridge['image_id'], 'bridge_mounts_sha256': bridge['mounts_sha256'],
             'bridge_runtime_sha256': bridge['runtime_sha256'], 'phase': 'pending'}
    summary = public_summary(state)
    save_state(state_path, state)
    return summary

def public_summary(state):
    # A matching digest does not make damaged persisted wire fields valid.
    try:
        if not isinstance(state, dict) or not isinstance(state['scope'], dict):
            raise ValueError()
        scope = state['scope']
        vehicle = scope['vehicle_id']
        if type(vehicle) is not int or not 1 <= vehicle <= 2147483647:
            raise ValueError()
        for key in ('source_instance_id', 'source_vehicle_id'):
            if not isinstance(scope[key], str) or not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', scope[key]):
                raise ValueError()
        fingerprint = state['scope_fingerprint']
        if (not isinstance(fingerprint, str) or not re.fullmatch(r'[0-9a-f]{16}', fingerprint) or
                scope_fingerprint(scope) != fingerprint):
            raise ValueError()
        handle, code, expiry, phase = (state[key] for key in ('handle', 'verification_code', 'expires_at', 'phase'))
        if (not isinstance(handle, str) or not re.fullmatch(r'[0-9a-f]{64}', handle) or
                not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code)):
            raise ValueError()
        if (not isinstance(expiry, str) or
                not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z', expiry)):
            raise ValueError()
        datetime.strptime(expiry[:19], '%Y-%m-%dT%H:%M:%S')
        if not isinstance(phase, str) or phase not in (
                'pending', 'credential_replacement_pending', 'credential_installed_recreate_pending',
                'installed_waiting_for_natural_upload'):
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise FlowError('comparison_scope_unverifiable') from None
    return {'phase': phase, 'verification_code': code,
            'expires_at': expiry,
            'scope_fingerprint': fingerprint,
            'app_link': AUTH + '/archive/enroll?handle=' + urllib.parse.quote(handle, safe='')}

def open_app(state_path, adb_path):
    state = read_json(state_path)
    devices = [line.split()[:2] for line in run([str(adb_path), 'devices']).splitlines()
               if re.fullmatch(r'\S+\s+(?:device|offline|unauthorized|unknown)', line.strip())]
    if len(devices) != 1 or devices[0][1] != 'device':
        raise FlowError('single_authorized_device_required')
    run([str(adb_path), '-s', devices[0][0], 'shell', 'am', 'start', '-a', 'android.intent.action.VIEW',
         '-d', public_summary(state)['app_link'], '-p', 'com.matelink'])
    return {'phase': 'waiting_for_owner_confirmation', 'verification_code': state['verification_code']}

def container_identity():
    value = run(['docker', 'inspect', '--format',
                 '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|'
                 '{{index .Config.Labels "com.docker.compose.service"}}|{{.Config.Image}}|{{.Image}}', CONTAINER])
    parts = value.split('|')
    if len(parts) != 5 or parts[1:3] != [PROJECT, SERVICE] or not re.fullmatch(r'sha256:[0-9a-f]{64}', parts[4]):
        raise FlowError('production_bridge_identity_changed')
    return parts

def verify_install_target(compose_path, expected_hash, expected_identity):
    if container_identity() != expected_identity:
        raise FlowError('production_bridge_identity_changed')
    if digest(compose_path.read_bytes()) != expected_hash:
        raise FlowError('production_compose_changed')

def recreate_and_verify_bridge(state):
    run(['docker', 'compose', '-p', PROJECT, '-f', str(bounded_path(state['compose_path'])), 'up', '-d', '--no-deps',
         '--no-build', '--pull', 'never', '--force-recreate', SERVICE])
    after = container_identity()
    if (after[3:5] != [state['bridge_image'], state['bridge_image_id']] or
            run(['docker', 'inspect', '--format', '{{.State.Running}}', CONTAINER]) != 'true'):
        raise FlowError('production_bridge_not_running')
    verify_bridge_snapshot(state)

def recover_missing_bridge(state, credential):
    if not isinstance(credential, str) or not re.fullmatch(r'[A-Za-z0-9_-]{24,512}', credential):
        raise FlowError('credential_response_invalid')
    compose = read_json(state['compose_path'])
    compose['services'][SERVICE]['environment']['ARCHIVE_TOKEN'] = credential
    replacement = (json.dumps(compose, ensure_ascii=False, indent=2) + '\n').encode()
    if digest(replacement) != state['installed_compose_sha256']:
        raise FlowError('issued_receipt_changed')
    # The trusted installed file already contains exactly this receipt. Do not
    # rewrite it or stop anything when the old container has truly disappeared.
    verify_missing_recreate_target(state)
    recreate_and_verify_bridge(state)

def install_credential(state_path, state, credential):
    if not isinstance(credential, str) or not re.fullmatch(r'[A-Za-z0-9_-]{24,512}', credential):
        raise FlowError('credential_response_invalid')
    compose_path = bounded_path(state['compose_path'])
    current_bytes = compose_path.read_bytes()
    captured_hash = digest(current_bytes)
    permitted = {state['original_compose_sha256'], state.get('installed_compose_sha256'),
                 state.get('pending_compose_sha256')}
    if captured_hash not in permitted:
        raise FlowError('production_compose_changed')
    compose = json.loads(current_bytes.decode('utf-8-sig'))
    if scope_fingerprint(scope_of(compose)) != state['scope_fingerprint']:
        raise FlowError('production_scope_changed')
    identity = container_identity()
    if state['bridge_image'] != identity[3] or state['bridge_image_id'] != identity[4]:
        raise FlowError('production_bridge_image_changed')
    verify_bridge_snapshot(state)
    state['bridge_image'] = identity[3]
    backup = bounded_path(state_path).with_name('compose-before-enrollment.json')
    if not backup.exists():
        write_private(backup, current_bytes)
    compose['services'][SERVICE]['environment']['ARCHIVE_TOKEN'] = credential
    replacement = (json.dumps(compose, ensure_ascii=False, indent=2) + '\n').encode()
    # Persist the intended non-secret digest before either stopping the bridge
    # or replacing its configuration. A lost process can reclaim the same
    # encrypted issued receipt and recognize both sides of the atomic replace.
    state['pending_compose_sha256'] = digest(replacement)
    state['phase'] = 'credential_replacement_pending'
    save_state(state_path, state)
    verify_install_target(compose_path, captured_hash, identity)
    run(['docker', 'stop', '--timeout', '15', identity[0]])
    verify_install_target(compose_path, captured_hash, identity)
    # Check again after the protected temporary has been flushed, immediately
    # before replacing the captured original/previous-pending configuration.
    write_private(compose_path, replacement,
                  before_replace=lambda: verify_install_target(compose_path, captured_hash, identity))
    state['installed_compose_sha256'] = digest(replacement)
    state.pop('pending_compose_sha256', None)
    state['phase'] = 'credential_installed_recreate_pending'
    save_state(state_path, state)
    verify_install_target(compose_path, state['installed_compose_sha256'], identity)
    recreate_and_verify_bridge(state)

def verify_installed_receipt_target(state):
    # A server ACK can survive a lost response while the local bridge later
    # stops. Confirm this durable installation and its current runtime before
    # removing the only local device proof used for receipt reconciliation.
    if (state.get('phase') != 'credential_installed_recreate_pending' or
            not re.fullmatch(r'[0-9a-f]{64}', state.get('installed_compose_sha256', ''))):
        raise FlowError('installed_receipt_unverifiable')
    identity = container_identity()
    verify_bridge_snapshot(state)
    if run(['docker', 'inspect', '--format', '{{.State.Running}}', CONTAINER]) != 'true':
        raise FlowError('production_bridge_not_running')
    verify_install_target(bounded_path(state['compose_path']), state['installed_compose_sha256'], identity)

def claim_and_install(state_path):
    state = read_json(state_path)
    if state.get('phase') == 'installed_waiting_for_natural_upload':
        return {'phase': state['phase']}
    compose_path = bounded_path(state['compose_path'])
    if digest(compose_path.read_bytes()) not in {state['original_compose_sha256'], state.get('installed_compose_sha256'),
                                               state.get('pending_compose_sha256')}:
        raise FlowError('production_compose_changed')
    missing = False
    try:
        verify_bridge_snapshot(state)
    except FlowError:
        if state.get('phase') != 'credential_installed_recreate_pending':
            raise
        verify_missing_recreate_target(state)
        missing = True
    _, data = request('/api/v1/archive/enrollments/' + state['handle'] + '/claim',
                      {'device_secret': state['device_secret']})
    if data.get('state') == 'pending':
        if missing:
            raise FlowError('issued_receipt_required')
        return {'phase': 'waiting_for_owner_confirmation'}
    if data.get('state') == 'installed':
        if missing:
            raise FlowError('production_bridge_missing')
        verify_installed_receipt_target(state)
        state['phase'] = 'installed_waiting_for_natural_upload'
        state.pop('device_secret', None)
        save_state(state_path, state)
        return {'phase': state['phase']}
    if data.get('state') != 'issued' or data.get('scope_fingerprint') != state['scope_fingerprint']:
        raise FlowError('issued_scope_mismatch')
    if missing:
        recover_missing_bridge(state, data.get('credential'))
    else:
        install_credential(state_path, state, data.get('credential'))
    request('/api/v1/archive/enrollments/' + state['handle'] + '/ack',
            {'device_secret': state['device_secret']})
    state['phase'] = 'installed_waiting_for_natural_upload'
    state.pop('device_secret', None)
    save_state(state_path, state)
    return {'phase': state['phase']}

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('prepare'); p.add_argument('--compose', required=True);p.add_argument('--state', required=True)
    p = sub.add_parser('open-app');p.add_argument('--state', required=True);p.add_argument('--adb', required=True)
    p = sub.add_parser('claim-install');p.add_argument('--state', required=True)
    options = parser.parse_args(argv)
    try:
        if options.action == 'prepare':result = prepare(options.compose, options.state)
        elif options.action == 'open-app':result = open_app(options.state, options.adb)
        else:result = claim_and_install(options.state)
        print(json.dumps(result, ensure_ascii=False));return 0
    except FlowError as error:
        print(json.dumps({'phase': 'error', 'code': str(error)}));return 1
    except Exception:
        print(json.dumps({'phase': 'error', 'code': 'local_recovery_failed'}));return 1

if __name__ == '__main__':
    sys.exit(main())
