"""Review candidate. Default dry-run. No Docker CLI, pull, TCP or discovery APIs.

Only --execute-local enables the fixed Unix socket. The guarded wrapper is a
mandatory part of the recovery port, not a property of the frozen core alone.
Python 3.6+ syntax; live execution deliberately requires Linux Python 3.6.
"""
import sys
# Default dry-run must not create __pycache__ even without a caller's -B flag.
sys.dont_write_bytecode = True

import argparse
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import stat
import threading
import time
import urllib.parse
import uuid

import recovery_core36 as p
from receipt_journal import PosixReceiptStore

ROOT = Path(__file__).absolute().parent
RUN_LABEL = "io.matelink.engine26.run"
FIXTURE_LABEL = "io.matelink.engine26.fixture"
PATH_ENV = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
IMAGE_RE = re.compile(r"sha256:[0-9a-f]{64}\Z")
ID_RE = re.compile(r"[0-9a-f]{64}\Z")
IDENTITY_CMD = ["/bin/sh", "-c", "id -u; id -g; awk '/^Uid:/{print $2,$3,$4,$5}/^Gid:/{print $2,$3,$4,$5}' /proc/1/status"]
STAT_CMD = ["stat", "-c", p.STAT_FORMAT, "--", "/fixture-config"]
CASES = ["rprivate-bind-named-user-port-two-networks", "wrong-uid-rejected",
         "bind-replaced-before-create", "bind-replaced-before-start", "symlink-rejected",
         "unsupported-bind-option-rejected", "port-competitor-first", "port-recovery-first",
         "name-competitor-first", "name-recovery-first", "concurrent-name-create",
         "same-session-concurrent-recover", "self-exit-unless-stopped-15s",
         "persistent-unhealthy-unless-stopped-15s"]


def execution_case_order(port_conflict_order):
    need(port_conflict_order in ("competitor-first", "recovery-first"), "explicit_port_conflict_order_required")
    ordinary = [case for case in CASES if case not in (CASES[6], CASES[7])]
    return ordinary + [CASES[6] if port_conflict_order == "competitor-first" else CASES[7]]


CASE_STAGES = frozenset(("CASE_BODY", "SOURCE_CREATE", "SOURCE_START", "CAPTURE", "PLAN_REGISTER", "SOURCE_REMOVE", "RECOVER", "CONCURRENT_CREATE"))
CASE_FAILURE_CODES = {
    "unsupported_port_binding_count": "PORT_BINDINGS_REJECTED",
    "unsupported_port_binding": "PORT_BINDINGS_REJECTED",
    "port_bindings_object_or_none_required": "PORT_BINDINGS_REJECTED",
    "loopback_port_required": "LOOPBACK_PORT_REJECTED",
    "fixed_host_port_required": "FIXED_PORT_REJECTED",
    "unexpected_recovery_outcome": "RECOVERY_OUTCOME_REJECTED",
    "receipt_store_required": "RECEIPT_STORE_REQUIRED",
    "below_memory_policy": "MEMORY_ADMISSION_REFUSED",
    "memavailable_unreadable": "MEMORY_ADMISSION_REFUSED",
    "api_not_allowlisted": "API_NOT_ALLOWLISTED",
    "api_path_rejected": "API_PATH_REJECTED",
    "ambiguous_query_rejected": "API_QUERY_REJECTED",
    "original_creation_receipt_required": "ORIGINAL_CREATION_RECEIPT_REQUIRED",
    "unresolved_existing_endpoint": "UNRESOLVED_EXISTING_ENDPOINT",
    "network_identity_changed": "NETWORK_IDENTITY_CHANGED",
    "network_mode_mapping_incomplete": "NETWORK_MODE_MAPPING_INCOMPLETE",
    "one_or_two_bridge_networks_required": "NETWORK_COUNT_REJECTED",
    "unknown_network_field": "NETWORK_FIELDS_REJECTED",
    "unsupported_network_driver": "NETWORK_DRIVER_REJECTED",
    "unsupported_network_mode": "NETWORK_MODE_REJECTED",
    "unsupported_network_topology": "NETWORK_TOPOLOGY_REJECTED",
    "unknown_endpoint_field": "ENDPOINT_FIELDS_REJECTED",
    "unsupported_endpoint_options": "ENDPOINT_OPTIONS_REJECTED",
    "unknown_ipam_field": "IPAM_FIELDS_REJECTED",
    "unknown_config_field": "CONFIG_FIELDS_REJECTED",
    "unknown_hostconfig_field": "HOSTCONFIG_FIELDS_REJECTED",
    "unsupported_hostconfig_value": "HOSTCONFIG_VALUE_REJECTED",
    "mount_mapping_incomplete": "MOUNT_MAPPING_INCOMPLETE",
    "resource_unavailable": "RESOURCE_UNAVAILABLE"}
RECOVERY_RESULT_CODES = {name: code for name, code in (
    ("healthy", "HEALTHY"), ("resource_changed", "RESOURCE_CHANGED"),
    ("plan_not_durable", "PLAN_NOT_DURABLE"), ("receipt_not_durable", "RECEIPT_NOT_DURABLE"),
    ("create_outcome_unknown", "CREATE_OUTCOME_UNKNOWN"), ("created_identity_unconfirmed", "CREATED_IDENTITY_UNCONFIRMED"),
    ("start_failed", "START_FAILED"), ("identity_mismatch", "IDENTITY_MISMATCH"),
    ("health_timeout", "HEALTH_TIMEOUT"), ("unhealthy", "UNHEALTHY"),
    ("operation_outcome_unknown", "OPERATION_OUTCOME_UNKNOWN"), ("name_occupied", "NAME_OCCUPIED"))}


class Blocked(Exception):
    """Constant public reason only; daemon responses and private objects stay in memory."""


def need(ok, reason):
    if not ok:
        raise Blocked(reason)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def fsync_directory(path):
    if isinstance(path, TrustedDirectory):
        path.sync()
        return
    if os.name == "posix":
        fd = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


class TrustedDirectory:
    """Root-owned, non-group/other-writable chain held by nofollow descriptors.

    Absolute names are rechecked against held inodes. All live local writes use
    the held final directory FD; a rename cannot redirect them to another tree.
    Root/administrator adversaries remain outside the threat model.
    """
    def __init__(self, path, chain, owned):
        self.path, self.chain, self.owned = path, chain, owned
        self.fd, self.closed = chain[-1][1], False

    @staticmethod
    def identity(value):
        return (value.st_dev, value.st_ino, value.st_mode, value.st_uid, value.st_gid)

    @staticmethod
    def check(value):
        need(stat.S_ISDIR(value.st_mode) and value.st_uid == 0 and not value.st_mode & 0o022,
             "untrusted_directory_owner_or_mode")

    @staticmethod
    def basename(name):
        need(isinstance(name, str) and re.fullmatch(r"[a-z][a-z0-9.-]{0,80}", name)
             and name not in (".", ".."), "relative_owned_basename_required")
        return name

    @classmethod
    def open(cls, path):
        text = str(path)
        need(text.startswith("/") and (text == "/" or all(x not in ("", ".", "..") for x in text[1:].split("/"))), "canonical_absolute_directory_required")
        need(hasattr(os, "O_DIRECTORY") and hasattr(os, "O_NOFOLLOW") and os.geteuid() == 0, "rootful_nofollow_directory_support_required")
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
        chain, owned = [], []
        try:
            fd = os.open("/", flags)
            owned.append(fd)
            value = os.fstat(fd)
            cls.check(value)
            chain.append(("/", fd, cls.identity(value)))
            for name in text[1:].split("/") if text != "/" else []:
                parent = fd
                fd = os.open(name, flags, dir_fd=parent)
                owned.append(fd)
                value = os.fstat(fd)
                cls.check(value)
                need(cls.identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) == cls.identity(value), "directory_replaced_during_open")
                chain.append((name, fd, cls.identity(value)))
            result = cls(text, chain, owned)
            result.verify()
            return result
        except BaseException:
            for fd in reversed(owned):
                os.close(fd)
            raise Blocked("untrusted_directory_chain") from None

    def verify(self):
        need(not self.closed, "trusted_directory_closed")
        try:
            for index, (name, fd, identity) in enumerate(self.chain):
                value = os.fstat(fd)
                self.check(value)
                need(self.identity(value) == identity, "held_directory_identity_changed")
                if index:
                    named = os.stat(name, dir_fd=self.chain[index - 1][1], follow_symlinks=False)
                    self.check(named)
                    need(self.identity(named) == identity, "trusted_directory_path_replaced")
        except OSError:
            raise Blocked("trusted_directory_unavailable") from None

    def sync(self):
        self.verify()
        os.fsync(self.fd)
        self.verify()

    def open_file(self, name, flags, mode=0o600):
        name = self.basename(name)
        self.verify()
        fd = None
        try:
            fd = os.open(name, flags | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0), mode, dir_fd=self.fd)
            value = os.fstat(fd)
            need(stat.S_ISREG(value.st_mode) and value.st_uid == 0 and not value.st_mode & 0o022
                 and value.st_nlink == 1, "untrusted_owned_file")
            self.verify()
            return fd
        except BaseException:
            if fd is not None:
                os.close(fd)
            raise Blocked("owned_file_open_failed") from None

    def mkdir_child(self, name, reuse=False):
        name = self.basename(name)
        self.verify()
        fd = None
        try:
            try:
                os.mkdir(name, 0o700, dir_fd=self.fd)
            except FileExistsError:
                need(reuse, "existing_directory_not_allowed")
            fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0), dir_fd=self.fd)
            value = os.fstat(fd)
            self.check(value)
            need(stat.S_IMODE(value.st_mode) == 0o700, "private_directory_0700_required")
            need(self.identity(os.stat(name, dir_fd=self.fd, follow_symlinks=False)) == self.identity(value), "child_directory_replaced")
            result = TrustedDirectory(self.path.rstrip("/") + "/" + name,
                                      self.chain + [(name, fd, self.identity(value))], [fd])
            result.verify()
            self.sync()
            return result
        except BaseException:
            if fd is not None:
                os.close(fd)
            raise Blocked("untrusted_child_directory") from None

    def unlink(self, name):
        self.verify()
        os.unlink(self.basename(name), dir_fd=self.fd)
        self.sync()

    def symlink(self, target, name):
        self.verify()
        os.symlink(self.basename(target), self.basename(name), dir_fd=self.fd)
        self.sync()

    def replace(self, source, target):
        self.verify()
        fd = self.open_file(source, os.O_PATH)
        os.close(fd)
        os.replace(self.basename(source), self.basename(target), src_dir_fd=self.fd, dst_dir_fd=self.fd)
        self.sync()

    def close(self):
        if not self.closed:
            for fd in reversed(self.owned):
                os.close(fd)
            self.closed = True


def no_symlink_directory(path):
    return TrustedDirectory.open(path)


class Journal:
    """Append-only JSONL. A successful append means bytes AND file fsync finished."""
    def __init__(self, directory, run):
        self.trusted = directory if isinstance(directory, TrustedDirectory) else None
        self.directory, self.run = Path(directory.path if self.trusted else directory), run
        self.lock = threading.RLock()
        self.events = []
        self.sequence = 0
        path = self.directory / "journal.jsonl"
        self.fd = (self.trusted.open_file("journal.jsonl", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                   if self.trusted else os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))
        fsync_directory(self.trusted or self.directory)
        self.append("run", run=run, engine="26.1.3", api="1.45", scope="synthetic-only")

    def append(self, event, **fields):
        with self.lock:
            if self.trusted:
                self.trusted.verify()
            self.sequence += 1
            record = dict(fields, event=event, sequence=self.sequence)
            data = canonical(record) + b"\n"
            offset = 0
            while offset < len(data):
                written = os.write(self.fd, data[offset:])
                need(written > 0, "journal_write_failed")
                offset += written
            os.fsync(self.fd)
            if self.trusted:
                self.trusted.verify()
            self.events.append(record)
            return self.sequence

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


class MemoryAdmission:
    """Parent-selected admission policy, not a bound on later host load."""
    def __init__(self, minimum_mib=384, recorder=None):
        need(type(minimum_mib) is int and minimum_mib >= 384, "minimum_available_mib_below_policy_floor")
        self.minimum_mib = minimum_mib
        self.threshold_kib = minimum_mib * 1024
        self.minimum_observed_kib = None
        self.events, self.recorder = [], recorder
        self.lock = threading.RLock()

    def check(self, operation):
        with self.lock:
            available = None
            try:
                with open("/proc/meminfo", "r", encoding="ascii") as stream:
                    for _ in range(64):
                        line = stream.readline(256)
                        if not line:
                            break
                        if line.startswith("MemAvailable:"):
                            fields = line.split()
                            if len(fields) == 3 and fields[1].isdigit() and fields[2] == "kB":
                                available = int(fields[1])
                            break
            except (OSError, UnicodeError):
                pass
            if available is not None:
                self.minimum_observed_kib = available if self.minimum_observed_kib is None else min(self.minimum_observed_kib, available)
            allowed = available is not None and available >= self.threshold_kib
            record = {"operation": operation, "available_kib": available,
                      "minimum_observed_kib": self.minimum_observed_kib,
                      "threshold_kib": self.threshold_kib, "allowed": allowed,
                      "reason": "accepted" if allowed else "memavailable_unreadable" if available is None else "below_memory_policy"}
            self.events.append(record)
            if self.recorder:
                self.recorder(record.copy())
            need(allowed, record["reason"])
            return record


class UnixTransport:
    """Fixed socket, no environment context, no DNS/TCP and no HTTP redirects."""
    def __init__(self):
        need(sys.platform.startswith("linux") and sys.version_info[:2] == (3, 6), "linux_python36_required")
        need(os.geteuid() == 0, "rootful_daemon_host_required")
        need(not any(os.environ.get(key) for key in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")), "docker_context_environment_rejected")
        value = os.lstat("/var/run/docker.sock")
        need(stat.S_ISSOCK(value.st_mode) and value.st_uid == 0, "fixed_root_owned_unix_socket_required")

    def request(self, method, path, body=None):
        connection = http.client.HTTPConnection("localhost", timeout=5)
        unix = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        unix.settimeout(5)
        try:
            unix.connect("/var/run/docker.sock")
            connection.sock = unix
            data = None if body is None else canonical(body)
            connection.request(method, "/v1.45" + path, body=data,
                               headers={"Content-Type": "application/json", "Connection": "close"})
            response = connection.getresponse()
            raw = response.read(1048577)
            need(len(raw) <= 1048576, "bounded_response_exceeded")
            if "application/json" in response.getheader("Content-Type", ""):
                result = json.loads(raw.decode("utf-8")) if raw else None
            else:
                result = raw if raw else None
            return response.status, result
        except Exception:
            raise Blocked("transport_outcome_unknown") from None
        finally:
            connection.close()
            unix.close()


class GuardedEngine:
    """The only transport used by fixtures and RecoverySession.

    All mutations are exact pre-registered templates or fixed probe/cleanup
    operations on received full IDs. No list, arbitrary inspect or name lookup.
    """
    def __init__(self, transport, journal, run, cached_image, owned_file, trusted_directory=None, memory_admission=None):
        need(IMAGE_RE.fullmatch(cached_image) is not None, "immutable_cached_image_required")
        self.transport, self.journal, self.run = transport, journal, run
        self.cached_image = cached_image
        self.images = {cached_image}
        self.owned_file = str(owned_file)
        self.trusted_directory = trusted_directory
        self.memory_admission = memory_admission
        if isinstance(transport, UnixTransport):
            need(isinstance(trusted_directory, TrustedDirectory) and journal.trusted is trusted_directory,
                 "live_transport_requires_trusted_directory_and_journal")
            need(type(memory_admission) is MemoryAdmission, "live_transport_requires_memory_admission")
        self.lock = threading.RLock()
        self._container_slots = {}
        self._allocation_closed = False
        self._allocation_closed_reason = None
        self._uncertain_posts = {}
        self.records = []
        self.templates = set()
        self.network_templates = set()
        self.commit_templates = set()
        self.counter = 0
        self.observations = []

    def labels(self):
        return {RUN_LABEL: self.run, FIXTURE_LABEL: "synthetic"}

    def name(self, suffix):
        need(re.fullmatch(r"[a-z][a-z0-9-]{0,40}", suffix) is not None, "invalid_fixture_suffix")
        with self.lock:
            self.counter += 1
            return self.run + "-" + suffix + "-" + str(self.counter)

    def valid_name(self, name):
        return isinstance(name, str) and re.fullmatch(re.escape(self.run) + r"-[a-z][a-z0-9-]{0,48}", name) is not None

    def record(self, kind, resource_id):
        with self.lock:
            found = [item for item in self.records if item["kind"] == kind and item["id"] == resource_id]
        need(len(found) == 1, "original_creation_receipt_required")
        return found[0]

    def network_ids(self):
        return {r["id"] for r in self.records if r["kind"] == "network"}

    @staticmethod
    def exact_container_limits(body):
        host = body.get("HostConfig", {}) if isinstance(body, dict) else {}
        expected = {"Memory": 16777216, "MemorySwap": 16777216, "PidsLimit": 16}
        need(all(type(host.get(key)) is int and host[key] == value for key, value in expected.items()),
             "exact_16m_memory_swap_16pid_limits_required")

    def _ensure_allocation_open(self):
        with self.lock:
            need(not self._allocation_closed, "run_allocation_closed")

    def _reserve_container(self):
        with self.lock:
            need(not self._allocation_closed, "run_allocation_closed")
            need(len(self._container_slots) < 2, "two_undeleted_container_limit")
            token = uuid.uuid4().hex
            self._container_slots[token] = {"state": "in_flight"}
            return token

    def _release_reservation(self, token):
        with self.lock:
            self._container_slots.pop(token, None)

    def _close_allocations(self, reason, token=None):
        with self.lock:
            self._allocation_closed = True
            self._allocation_closed_reason = reason
            if token in self._container_slots:
                self._container_slots[token]["state"] = "unknown"

    def _mark_post_unknown(self, state, reason, status=None):
        # Seal first: journal failure must never leave allocation open.
        self._close_allocations(reason, state["token"])
        with self.lock:
            item = self._uncertain_posts.setdefault(state["plan"], {
                "plan": state["plan"], "api_kind": state["kind"], "reason": reason,
                "http_status": status, "unknown_record_persisted": False})
        if state["unknown_attempted"]:
            return
        state["unknown_attempted"] = True
        self.journal.append("unknown", plan=state["plan"], api_kind=state["kind"], reason=reason, http_status=status)
        with self.lock:
            item["unknown_record_persisted"] = True

    def uncertain_posts(self):
        with self.lock:
            return [copy.deepcopy(value) for _, value in sorted(self._uncertain_posts.items())]

    @staticmethod
    def _valid_exec_stream(payload):
        if not isinstance(payload, bytes) or not payload:
            return False
        position = 0
        while position < len(payload):
            if len(payload) - position < 8 or payload[position] not in (1, 2) or payload[position + 1:position + 4] != b"\0\0\0":
                return False
            length = int.from_bytes(payload[position + 4:position + 8], "big")
            position += 8 + length
            if position > len(payload):
                return False
        return position == len(payload)

    def _confirm_container_absent(self, resource_id):
        with self.lock:
            matches = [token for token, item in self._container_slots.items()
                       if item.get("state") == "known" and item.get("id") == resource_id]
        if not matches:
            return
        self.journal.append("container_absent", id=resource_id, verified_404=True)
        with self.lock:
            for token in matches:
                self._container_slots.pop(token, None)

    def container_budget(self):
        with self.lock:
            return {"limit": 2, "reserved": len(self._container_slots),
                    "known": sum(x["state"] == "known" for x in self._container_slots.values()),
                    "in_flight": sum(x["state"] == "in_flight" for x in self._container_slots.values()),
                    "unknown": sum(x["state"] == "unknown" for x in self._container_slots.values()),
                    "allocation_closed": self._allocation_closed,
                    "closed_reason": self._allocation_closed_reason}

    def permit_container(self, name, body):
        self.exact_container_limits(body)
        with self.lock:
            need(not self._allocation_closed, "run_allocation_closed")
            need(len(self._container_slots) < 2, "two_undeleted_container_limit")
        need(self.valid_name(name), "random_run_name_required")
        need(isinstance(body, dict) and not set(body) - (p.CONFIG_KEYS | {"HostConfig", "NetworkingConfig"}), "unknown_fixture_field")
        need(body.get("Labels") == self.labels() and body.get("Image") in self.images, "fixture_image_labels_required")
        env = body.get("Env", [])
        need(isinstance(env, list) and all(x in (PATH_ENV, "SYNTHETIC_RUN=" + self.run) for x in env), "synthetic_environment_only")
        need("SYNTHETIC_RUN=" + self.run in env, "run_payload_required")
        host = body.get("HostConfig", {})
        need(not host.get("Privileged") and not host.get("CapAdd") and not host.get("Devices")
             and not host.get("DeviceRequests") and not host.get("VolumesFrom") and not host.get("AutoRemove"), "unsafe_fixture_hostconfig")
        need(not host.get("Mounts") and not body.get("Volumes"), "fixture_volume_mounts_disabled")
        need(host.get("NetworkMode") in self.network_ids() | {"none"}, "own_network_required")
        need(not host.get("PidMode") and host.get("IpcMode", "private") == "private"
             and not host.get("UsernsMode") and not host.get("UTSMode"), "host_namespace_rejected")
        for binding in host.get("Binds") or []:
            need(binding == self.owned_file + ":/fixture-config:ro", "only_own_empty_file_bind_allowed")
        endpoints = body.get("NetworkingConfig", {}).get("EndpointsConfig", {})
        need(set(endpoints) <= self.network_ids(), "only_own_endpoints_allowed")
        for endpoint in endpoints.values():
            need(set(endpoint) <= {"Aliases", "IPAMConfig"} and not endpoint.get("IPAMConfig"), "dynamic_endpoints_only")
        bindings = host.get("PortBindings", {})
        if bindings is None:
            bindings = {}
        need(isinstance(bindings, dict), "port_bindings_object_or_none_required")
        need(not bindings or set(bindings) == {"8080/tcp"}, "one_synthetic_port_only")
        if bindings:
            need(len(bindings["8080/tcp"]) == 1, "one_loopback_binding_only")
            binding = bindings["8080/tcp"][0]
            need(set(binding) == {"HostIp", "HostPort"} and binding["HostIp"] == "127.0.0.1"
                 and isinstance(binding["HostPort"], str) and binding["HostPort"].isdigit()
                 and 0 <= int(binding["HostPort"]) <= 65535, "synthetic_loopback_binding_required")
            need(binding["HostPort"] == "0" or binding["HostPort"] == getattr(self, "picked_port", None), "own_picker_port_only")
        with self.lock:
            self.templates.add((name, digest(body)))

    def permit_network(self, body):
        need(self.valid_name(body.get("Name")) and body.get("Labels") == self.labels(), "own_network_name_labels_required")
        need(set(body) == {"Name", "Labels", "Driver", "Internal", "EnableIPv6", "IPAM", "Options"}
             and body["Driver"] == "bridge" and body["EnableIPv6"] is False, "fixed_bridge_only")
        need(body["IPAM"] in [{"Config": [{"Subnet": x, "Gateway": y}]} for x, y in
                             (("192.0.2.0/28", "192.0.2.1"), ("198.51.100.0/28", "198.51.100.1"),
                              ("203.0.113.0/28", "203.0.113.1"))], "documentation_subnet_only")
        need(body["Options"] == {"com.docker.network.bridge.enable_ip_masquerade": "false",
                                 "com.docker.network.bridge.enable_icc": "false"}, "fixed_bridge_options_only")
        self.network_templates.add(digest(body))

    def permit_commit(self, seed, tag, body):
        self.record("container", seed)
        need(tag == self.run + "-fixture-image:probe" and body == {"Labels": self.labels()}, "unique_fixture_commit_only")
        self.commit_templates.add((seed, tag, digest(body)))

    def _route(self, method, path, body):
        parsed = urllib.parse.urlsplit(path)
        need(not parsed.scheme and not parsed.netloc and not parsed.fragment and not parsed.path.startswith("//"), "api_path_rejected")
        route, query = parsed.path, urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        need(urllib.parse.urlencode([(k, v) for k in sorted(query) for v in query[k]]) ==
             urllib.parse.urlencode(sorted(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))), "ambiguous_query_rejected")
        if method == "GET" and route == "/version" and not query and body is None:
            return {"kind": "version"}
        if method == "POST" and route == "/containers/create" and set(query) == {"name"} and len(query["name"]) == 1:
            self.exact_container_limits(body)
            name = query["name"][0]
            need((name, digest(body)) in self.templates, "unregistered_container_template")
            return {"kind": "container", "name": name, "labels": self.labels(), "body_sha256": digest(body)}
        if method == "POST" and route == "/networks/create" and not query:
            need(digest(body) in self.network_templates, "unregistered_network_template")
            return {"kind": "network", "name": body["Name"], "labels": self.labels(), "body_sha256": digest(body)}
        if method == "POST" and route == "/commit" and set(query) == {"container", "pause", "repo", "tag"} and all(len(v) == 1 for v in query.values()):
            seed, tag = query["container"][0], query["repo"][0] + ":" + query["tag"][0]
            need(query["pause"] == ["1"] and (seed, tag, digest(body)) in self.commit_templates, "unregistered_commit_template")
            need(self._fresh_static(self.record("container", seed)) is not None, "seed_absent")
            return {"kind": "image", "name": tag, "labels": self.labels(), "body_sha256": digest(body)}
        match = re.fullmatch(r"/images/(sha256:[0-9a-f]{64})/json", route)
        if method == "GET" and match and not query and body is None:
            need(match[1] in self.images, "unknown_image_rejected")
            return {"kind": "image_inspect", "id": match[1]}
        match = re.fullmatch(r"/(containers|networks)/([0-9a-f]{64})(/json|/start|/exec)?", route)
        if match:
            kind = "container" if match[1] == "containers" else "network"
            record = self.record(kind, match[2])
            suffix = match[3] or ""
            if method == "GET" and not query and body is None and suffix == ("/json" if kind == "container" else ""):
                return {"kind": kind + "_inspect", "id": match[2]}
            if kind == "container" and method == "POST" and suffix == "/start" and not query and body is None:
                need(self._fresh_static(record) is not None, "owned_container_absent")
                return {"kind": "start", "id": match[2]}
            if kind == "container" and method == "POST" and suffix == "/exec" and not query:
                need(body in ({"Cmd": IDENTITY_CMD, "AttachStdout": True, "AttachStderr": True, "Tty": False},
                              {"Cmd": STAT_CMD, "AttachStdout": True, "AttachStderr": True, "Tty": False}), "fixed_nonsecret_probe_only")
                need(self._fresh_static(record) is not None, "owned_container_absent")
                return {"kind": "exec", "name": record["name"], "labels": self.labels(), "container_id": match[2], "body_sha256": digest(body)}
            if method == "DELETE" and suffix == "" and body is None:
                need(query == ({"force": ["1"], "v": ["0"]} if kind == "container" else {}), "cleanup_options_rejected")
                item = self._fresh_static(record)
                need(item is not None, "already_absent")
                need(kind != "network" or not item.get("Containers"), "network_has_attachments")
                return {"kind": "delete", "id": match[2]}
        match = re.fullmatch(r"/exec/([0-9a-f]{64})/(start|json)", route)
        if match and not query:
            record = self.record("exec", match[1])
            if method == "POST" and match[2] == "start" and body == {"Detach": False, "Tty": False}:
                return {"kind": "exec_start", "id": match[1]}
            if method == "GET" and match[2] == "json" and body is None:
                return {"kind": "exec_inspect", "id": match[1], "container_id": record["container_id"]}
        match = re.fullmatch(r"/images/(sha256:[0-9a-f]{64})", route)
        if method == "DELETE" and match and body is None:
            need(match[1] != self.cached_image, "cached_image_delete_forbidden")
            need(parsed.query == "noprune=1" and query == {"noprune": ["1"]}, "image_delete_requires_exact_noprune")
            record = self.record("image", match[1])
            need(self._fresh_static(record) is not None, "owned_image_absent")
            return {"kind": "delete", "id": match[1]}
        raise Blocked("api_not_allowlisted")

    def _fingerprint(self, kind, item):
        if kind == "container":
            return digest({"Config": item["Config"], "HostConfig": p.canonical_host(item["HostConfig"]),
                           "Mounts": p.canonical_mounts(item["Mounts"])})
        if kind == "network":
            return digest(p.network_identity(item))
        return digest({"Id": item["Id"], "Labels": item.get("Config", {}).get("Labels"), "RepoTags": item.get("RepoTags")})

    def _verify(self, kind, record, item):
        need(item.get("Id") == record["id"], "returned_full_id_mismatch")
        labels = item.get("Config", {}).get("Labels", {}) if kind in ("container", "image") else item.get("Labels", {})
        need(isinstance(labels, dict) and all(labels.get(k) == v for k, v in record["labels"].items()), "owner_labels_mismatch")
        if kind == "container":
            need(item.get("Name") == "/" + record["name"], "returned_name_mismatch")
            need(labels == record["labels"], "container_labels_changed")
        elif kind == "network":
            need(item.get("Name") == record["name"] and labels == record["labels"], "network_name_labels_changed")
        else:
            need(item.get("RepoTags") == [record["name"]], "image_unique_tag_changed")
        fingerprint = self._fingerprint(kind, item)
        with self.lock:
            if "static_sha256" not in record:
                # First observed static fingerprint must be durable before start/delete.
                self.journal.append("static", kind=kind, id=record["id"], static_sha256=fingerprint)
                record["static_sha256"] = fingerprint
            need(record["static_sha256"] == fingerprint, "static_configuration_changed")

    def _fresh_static(self, record):
        path = {"container": "/containers/" + record["id"] + "/json",
                "network": "/networks/" + record["id"],
                "image": "/images/" + record["id"] + "/json"}[record["kind"]]
        status, item = self.request("GET", path)
        if status == 404:
            return None
        need(status == 200, "owned_inspect_unavailable")
        return item

    def request(self, method, path, body=None):
        body = copy.deepcopy(body)
        intent = self._route(method, path, body)
        allocating = method == "POST" and intent["kind"] in ("container", "network", "image", "start", "exec", "exec_start")
        if allocating:
            self._ensure_allocation_open()
        state = {"token": self._reserve_container() if intent["kind"] == "container" else None,
                 "sent": False, "definite_refusal": False, "plan": None,
                 "post": method == "POST", "kind": intent["kind"], "unknown_attempted": False}
        try:
            return self._request_intent(method, path, body, intent, allocating, state)
        except BaseException:
            if state["sent"] and state["post"] and not state["definite_refusal"]:
                try:
                    self._mark_post_unknown(state, "post_outcome_unknown")
                except BaseException:
                    # The pre-POST plan remains; closure already happened and the
                    # failed unknown-record attempt is visible in uncertain_posts.
                    pass
            elif state["sent"] and method == "DELETE":
                try:
                    self.journal.append("unknown", plan=state["plan"], reason="transport_or_reply_outcome_unknown")
                except BaseException:
                    pass
            elif state["token"] is not None and not state["sent"]:
                self._release_reservation(state["token"])
            raise

    def _request_intent(self, method, path, body, intent, allocating, state):
        mutation = method != "GET"
        if mutation:
            if self.trusted_directory:
                self.trusted_directory.verify()
            state["plan"] = self.journal.append("plan", method=method, api_kind=intent["kind"], intent=intent)
            if self.trusted_directory:
                self.trusted_directory.verify()
        if allocating:
            self._ensure_allocation_open()
            if self.memory_admission:
                try:
                    self.memory_admission.check(intent["kind"])
                except BaseException:
                    self._close_allocations("memory_admission_refused")
                    self.journal.append("reply", plan=state["plan"], http_status=0, sent=False, reason="memory_admission_refused")
                    raise
            self._ensure_allocation_open()
        state["sent"] = True
        status, payload = self.transport.request(method, path, body)
        if state["post"] and status in (400, 401, 403, 404, 409, 422):
            state["definite_refusal"] = True
            if state["token"] is not None:
                self._release_reservation(state["token"])
        elif state["post"]:
            accepted = {"container": (201,), "network": (201,), "image": (201,),
                        "exec": (201,), "start": (204, 304), "exec_start": (200,)}
            if status not in accepted[intent["kind"]]:
                self._mark_post_unknown(state, "post_http_outcome_unknown", status)
                return status, payload  # Never persist an ordinary reply for uncertainty.
            if ((intent["kind"] == "start" and payload is not None)
                    or (intent["kind"] == "exec_start" and not self._valid_exec_stream(payload))):
                self._mark_post_unknown(state, "invalid_start_response", status)
                raise Blocked("invalid_start_response")
        if status == 201:
            need(intent["kind"] in ("container", "network", "image", "exec"), "unexpected_201")
            resource_id = payload.get("Id") if isinstance(payload, dict) else None
            valid = isinstance(resource_id, str) and (IMAGE_RE.fullmatch(resource_id) if intent["kind"] == "image" else ID_RE.fullmatch(resource_id))
            if not valid:
                self._mark_post_unknown(state, "invalid_201_id", 201)
                raise Blocked("invalid_201_id")
            if intent["kind"] == "image" and resource_id == self.cached_image:
                self._mark_post_unknown(state, "cached_image_commit_id_forbidden", 201)
                raise Blocked("cached_image_commit_id_forbidden")
            receipt = dict(intent, id=resource_id, plan=state["plan"])
            self.journal.append("receipt", **receipt)
            with self.lock:
                need(not any(r["kind"] == receipt["kind"] and r["id"] == resource_id for r in self.records), "duplicate_receipt_id")
                self.records.append(receipt)
                if receipt["kind"] == "container":
                    self._container_slots[state["token"]] = {"state": "known", "id": resource_id}
                if receipt["kind"] == "image":
                    self.images.add(resource_id)
        elif mutation:
            self.journal.append("reply", plan=state["plan"], http_status=status)
        if status == 404 and intent["kind"] == "container_inspect":
            self._confirm_container_absent(intent["id"])
        if status == 200 and intent["kind"].endswith("_inspect"):
            kind = intent["kind"].replace("_inspect", "")
            need(isinstance(payload, dict), "inspect_payload_invalid")
            if kind == "exec":
                need(payload.get("ID", payload.get("Id")) == intent["id"] and payload.get("ContainerID") == intent["container_id"], "exec_identity_mismatch")
            elif kind == "image" and intent["id"] == self.cached_image:
                need(payload.get("Id") == self.cached_image, "cached_image_identity_changed")
            else:
                self._verify(kind, self.record(kind, intent["id"]), payload)
                if kind == "container":
                    state = payload.get("State", {})
                    health = state.get("Health", {})
                    self.observations.append({"id": intent["id"], "monotonic": time.monotonic(),
                        "pid": state.get("Pid"), "started_at": state.get("StartedAt"),
                        "restart_count": payload.get("RestartCount"), "health": health.get("Status"),
                        "probes": [{k: x.get(k) for k in ("Start", "End", "ExitCode")} for x in health.get("Log", [])]})
        return status, payload

    def expect(self, method, path, body=None, codes=(200, 201, 204)):
        status, payload = self.request(method, path, body)
        need(status in codes, "fixture_http_failure")
        return payload

    def create(self, name, body):
        self.permit_container(name, body)
        return self.expect("POST", "/containers/create?" + urllib.parse.urlencode({"name": name}), body)["Id"]

    def inspect(self, resource_id):
        return self.expect("GET", "/containers/" + resource_id + "/json")

    def remove(self, resource_id):
        record = self.record("container", resource_id)
        if self._fresh_static(record) is None:
            return "absent"
        self.expect("DELETE", "/containers/" + resource_id + "?force=1&v=0")
        status, _ = self.request("GET", "/containers/" + resource_id + "/json")
        need(status == 404, "cleanup_absence_unproved")
        return "removed"

    def cleanup(self):
        failures, absent = [], 0
        # Exec objects have no independent DELETE API and expire with their parent.
        for kind in ("container", "network", "image"):
            for record in list(reversed(self.records)):
                if record["kind"] != kind:
                    continue
                try:
                    if self._fresh_static(record) is not None:
                        if kind == "container":
                            route = "/containers/" + record["id"] + "?force=1&v=0"
                        elif kind == "image":
                            route = "/images/" + record["id"] + "?noprune=1"
                        else:
                            route = "/" + kind + "s/" + record["id"]
                        self.expect("DELETE", route)
                    need(self._fresh_static(record) is None, "cleanup_absence_unproved")
                    absent += 1
                except BaseException:
                    failures.append({"kind": kind, "id": record["id"], "reason": "cleanup_retained_for_review"})
        unknown = [e["sequence"] for e in self.journal.events if e["event"] == "plan"
                   and not any(x.get("plan") == e["sequence"] and x["event"] in ("receipt", "reply") for x in self.journal.events)]
        result = {"known_resources_absent": absent, "retained": failures, "unknown_plans": unknown,
                  "cached_image_deleted": False, "enumeration": False, "force_image_delete": False,
                  "container_budget": self.container_budget(), "uncertain_posts": self.uncertain_posts()}
        self.journal.append("cleanup", **result)
        return result


def file_identity(path, trusted):
    """Read only metadata from a runner-created file, no file-content reads."""
    need(str(path) == trusted.path + "/synthetic-empty", "owned_empty_basename_required")
    fd = trusted.open_file("synthetic-empty", os.O_PATH)
    try:
        x = os.fstat(fd)
        need(stat.S_ISREG(x.st_mode) and x.st_size == 0 and x.st_nlink == 1, "own_empty_regular_file_required")
        return {"dev": x.st_dev, "ino": x.st_ino, "mode": x.st_mode, "uid": x.st_uid,
                "gid": x.st_gid, "size": x.st_size, "nlink": x.st_nlink,
                "mtime_ns": x.st_mtime_ns, "ctime_ns": x.st_ctime_ns}
    finally:
        os.close(fd)


class OwnBindProbe:
    def __init__(self, path, engine, witness, trusted):
        self.path = str(path)
        self.trusted = trusted
        trusted.verify()
        local = file_identity(path, trusted)
        need(p.mounted_metadata(engine, witness, "/fixture-config") == local, "daemon_filesystem_witness_mismatch")
        # Only now attest the namespace to the frozen provider.
        self.native = p.NativeBindProbe(daemon_filesystem_attested=True)
        need(self.native.describe(self.path)["file"] == local, "namespace_changed_during_attestation")

    def describe(self, source):
        need(source == self.path, "only_runner_created_bind_source_allowed")
        self.trusted.verify()
        value = self.native.describe(source)
        self.trusted.verify()
        need(value["file"]["size"] == 0 and value["file"]["nlink"] == 1, "own_empty_regular_file_required")
        return value


class Hook:
    def __init__(self, engine, after_create=None):
        self.engine, self.after_create = engine, after_create
        self.starts = []

    def request(self, method, path, body=None):
        if method == "POST" and re.fullmatch(r"/containers/[0-9a-f]{64}/start", path):
            self.starts.append(path.split("/")[2])
        status, result = self.engine.request(method, path, body)
        if method == "POST" and path.startswith("/containers/create?") and status == 201 and self.after_create:
            action, self.after_create = self.after_create, None
            action(result["Id"])
        return status, result


def make_native_receipt_store(directory):
    """Live wiring: no mock, adapter or subclass may replace the native store."""
    need(isinstance(directory, TrustedDirectory), "native_store_requires_trusted_directory")
    directory.verify()
    leaf = directory.mkdir_child("core-receipts")
    try:
        store = PosixReceiptStore(leaf.path)
        need(type(store) is PosixReceiptStore, "exact_native_receipt_store_required")
        return store, leaf
    except BaseException:
        leaf.close()
        raise


class LiveSuite:
    def __init__(self, engine, directory, *, receipt_store=None, port_conflict_order=None):
        need(isinstance(directory, TrustedDirectory), "live_suite_requires_trusted_directory")
        need(type(receipt_store) is PosixReceiptStore, "exact_native_receipt_store_required")
        self.e, self.trusted, self.directory = engine, directory, Path(directory.path)
        self.receipt_store = receipt_store
        self.port_conflict_order = port_conflict_order
        self.case_order = execution_case_order(port_conflict_order)
        self._run_started = False
        self._expected_start_failure_id = None
        self.results = []
        self.port = None
        self.probe = None
        self.image = engine.cached_image

    def empty_file(self):
        fd = self.trusted.open_file("synthetic-empty", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        self.trusted.sync()

    def replace_file(self):
        fd = self.trusted.open_file("replacement-empty", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
        os.fsync(fd)
        os.close(fd)
        self.trusted.replace("replacement-empty", "synthetic-empty")

    def base(self, command=None, user="fixtureapp"):
        return {"Image": self.image, "User": user, "Cmd": command or ["sleep", "300"],
                "Env": ["SYNTHETIC_RUN=" + self.e.run], "Labels": self.e.labels(),
                "HostConfig": {"NetworkMode": "none", "ReadonlyRootfs": True,
                               "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges:true"],
                               "Memory": 16777216, "MemorySwap": 16777216, "PidsLimit": 16,
                               "RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}}}

    def wait_exit(self, cid):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            item = self.e.inspect(cid)
            if not item["State"]["Running"]:
                need(item["State"]["Status"] == "exited" and item["State"]["ExitCode"] == 0, "seed_exit_failed")
                return
            time.sleep(.1)
        raise Blocked("seed_exit_timeout")

    def setup(self):
        version = self.e.expect("GET", "/version")
        need(version.get("Version") == "26.1.3" and version.get("ApiVersion") == "1.45" and version.get("Os") == "linux", "matching_engine26_required")
        cached = self.e.expect("GET", "/images/" + self.image + "/json")
        config = cached.get("Config", {})
        need(any(re.fullmatch(r"alpine:[A-Za-z0-9_.-]+", t) for t in cached.get("RepoTags", [])), "explicit_cached_alpine_required")
        need(config.get("Env") == [PATH_ENV] and config.get("Cmd") == ["/bin/sh"]
             and not config.get("User") and not config.get("Entrypoint") and not config.get("Labels")
             and not config.get("Volumes") and not config.get("ExposedPorts") and not config.get("OnBuild"), "stock_alpine_shape_required")
        seed_body = self.base(["sh", "-c", "addgroup -g 10001 fixturegrp && adduser -D -H -u 10001 -G fixturegrp fixtureapp"], "0:0")
        seed_body["HostConfig"]["ReadonlyRootfs"] = False
        seed_body["HostConfig"]["CapDrop"] = None
        seed = self.e.create(self.e.name("named-user-seed"), seed_body)
        self.e.expect("POST", "/containers/" + seed + "/start")
        self.wait_exit(seed)
        body = {"Labels": self.e.labels()}
        tag = self.e.run + "-fixture-image:probe"
        self.e.permit_commit(seed, tag, body)
        query = urllib.parse.urlencode({"container": seed, "pause": "1", "repo": tag.split(":")[0], "tag": "probe"})
        self.image = self.e.expect("POST", "/commit?" + query, body)["Id"]
        self.e.expect("GET", "/images/" + self.image + "/json")
        self.e.remove(seed)
        self.nets = []
        for index, (subnet, gateway) in enumerate((("192.0.2.0/28", "192.0.2.1"), ("198.51.100.0/28", "198.51.100.1"), ("203.0.113.0/28", "203.0.113.1"))):
            body = {"Name": self.e.name("network"), "Driver": "bridge", "Internal": index != 2,
                    "EnableIPv6": False, "Labels": self.e.labels(), "IPAM": {"Config": [{"Subnet": subnet, "Gateway": gateway}]},
                    "Options": {"com.docker.network.bridge.enable_ip_masquerade": "false", "com.docker.network.bridge.enable_icc": "false"}}
            self.e.permit_network(body)
            net = self.e.expect("POST", "/networks/create", body)["Id"]
            self.e.expect("GET", "/networks/" + net)
            self.nets.append(net)
        self.empty_file()
        witness_body = self.base(user="0:0")
        witness_body["HostConfig"]["Binds"] = [self.e.owned_file + ":/fixture-config:ro"]
        witness = self.e.create(self.e.name("metadata-witness"), witness_body)
        self.e.expect("POST", "/containers/" + witness + "/start")
        self.probe = OwnBindProbe(Path(self.e.owned_file), self.e, witness, self.trusted)
        self.e.remove(witness)
        picker = self.e.create(self.e.name("port-picker"), self.port_body("0"))
        self.e.expect("POST", "/containers/" + picker + "/start")
        ports = self.e.inspect(picker)["NetworkSettings"]["Ports"]["8080/tcp"]
        need(len(ports) == 1 and ports[0]["HostIp"] == "127.0.0.1" and ports[0]["HostPort"].isdigit()
             and 1 <= int(ports[0]["HostPort"]) <= 65535, "own_random_loopback_port_unproved")
        self.port = self.e.picked_port = ports[0]["HostPort"]
        self.e.journal.append("port_proof", picker_id=picker, name_labels_id_verified=True, loopback=True, value_persisted=False)
        self.e.remove(picker)

    def port_body(self, port):
        body = self.base()
        body["ExposedPorts"] = {"8080/tcp": {}}
        body["HostConfig"].update(NetworkMode=self.nets[2], PortBindings={"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": port}]})
        return body

    def profile(self, bind=True, ports=True):
        body = self.base()
        primary = self.nets[2] if ports else self.nets[0]
        body["HostConfig"]["NetworkMode"] = primary
        body["NetworkingConfig"] = {"EndpointsConfig": {primary: {"Aliases": [self.e.run + "-api"]}, self.nets[1]: {"Aliases": [self.e.run + "-second"]}}}
        body["Healthcheck"] = {"Test": ["CMD-SHELL", "test -f /fixture-config && test -r /fixture-config && test ! -w /fixture-config" if bind else "exit 0"],
                               "Interval": 1000000000, "Timeout": 1000000000, "Retries": 2}
        if bind:
            body["HostConfig"]["Binds"] = [self.e.owned_file + ":/fixture-config:ro"]
        if ports:
            body["ExposedPorts"] = {"8080/tcp": {}}
            body["HostConfig"]["PortBindings"] = {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": self.port}]}
        return body

    def session(self, body=None, hook=None, identity=(10001, 10001), target=None):
        body = body or self.profile()
        target = target or self.e.name("recovery")
        self._set_case_stage("SOURCE_CREATE")
        source = self.e.create(target, body)
        if body["HostConfig"].get("Binds"):
            self._set_case_stage("SOURCE_START")
            self.e.expect("POST", "/containers/" + source + "/start")
        engine = hook or Hook(self.e)
        self._set_case_stage("CAPTURE")
        session = p.RecoverySession.capture(engine, source, target, generated_mac_attested=True,
                    expected_identity=identity, bind_metadata=self.probe, receipt_store=self.receipt_store)
        self._set_case_stage("PLAN_REGISTER")
        self.e.permit_container(target, session._plan.body())
        self._set_case_stage("SOURCE_REMOVE")
        self.e.remove(source)
        return session, engine, target

    def outcome(self, session, status="healthy", timeout=12):
        self._set_case_stage("RECOVER")
        result = session.recover(health_timeout=timeout)
        code = RECOVERY_RESULT_CODES.get(result.status, "OTHER_CORE_RESULT") if type(result.status) is str else "OTHER_CORE_RESULT"
        self.e.journal.append("recovery_result", stage="RECOVER", code=code)
        need(result.status == status, "unexpected_recovery_outcome")
        if status == "healthy":
            need(result.successful_health_probes >= 3, "three_distinct_probes_required")
        return result

    def _set_case_stage(self, stage):
        need(stage in CASE_STAGES, "invalid_case_stage")
        self._case_stage = stage
        self.e.journal.append("case_stage", stage=stage)

    def _record_case_failure(self, index, error):
        stage = getattr(self, "_case_stage", "CASE_BODY")
        stage = stage if stage in CASE_STAGES else "CASE_BODY"
        if isinstance(error, (Blocked, p.RecoveryBlocked)):
            reason = error.args[0] if len(error.args) == 1 and type(error.args[0]) is str else None
            code = CASE_FAILURE_CODES.get(reason, "CONTRACT_REJECTED")
        else:
            code = "LOCAL_IO_FAILURE" if isinstance(error, OSError) else "UNEXPECTED_EXCEPTION"
        fields = {"case_index": index, "stage": stage, "code": code}
        try:
            self.e.journal.append("case_failure", **fields)
            fields["log_status"] = "PERSISTED"
        except BaseException:
            fields["log_status"] = "UNAVAILABLE"
        print("Jovi, " + json.dumps({"case_failure": fields}, sort_keys=True), flush=True)

    def run_case(self, name, fn):
        index = len(self.results)
        need(index < len(self.case_order) and self.case_order[index] == name, "case_order_changed")
        terminal = index == len(self.case_order) - 1
        print("Jovi, [{}/{}] {}".format(index + 1, len(self.case_order), name), flush=True)
        started = time.monotonic()
        try:
            self._set_case_stage("CASE_BODY")
            fn()
            closed = self.e.container_budget()["allocation_closed"]
            need(not closed or terminal, "unexpected_early_allocation_closure")
            if terminal:
                need(closed and self._expected_start_failure_id is not None, "terminal_expected_start_failure_missing")
            self.results.append({"case": name, "status": "pass", "elapsed_seconds": round(time.monotonic() - started, 3)})
            fields = dict(self.results[-1], terminal=terminal, case_index=index + 1, total_cases=len(self.case_order))
            if terminal:
                fields["expected_start_failure_id"] = self._expected_start_failure_id
            self.e.journal.append("case", **fields)
        except BaseException as error:
            self._record_case_failure(index + 1, error)
            raise

    def reconcile_expected_start_failure(self, cleanup):
        """Evidence-only terminal reconciliation; original unknown/seal stay intact."""
        need(len(self.results) == len(self.case_order) and self.case_order == execution_case_order(self.port_conflict_order)
             and all(item.get("case") == name and item.get("status") == "pass" for item, name in zip(self.results, self.case_order)), "suite_not_complete")
        need(not cleanup.get("retained") and not cleanup.get("cleanup_failed"), "known_cleanup_not_complete")
        budget = self.e.container_budget()
        need(budget["allocation_closed"] and budget["reserved"] == 0, "terminal_run_not_sealed_or_capacity_pending")
        known = sum(x["kind"] in ("container", "network", "image") for x in self.e.records)
        need(cleanup.get("known_resources_absent") == known, "known_resource_absence_count_mismatch")
        target = self._expected_start_failure_id
        need(isinstance(target, str) and ID_RE.fullmatch(target), "expected_full_id_required")
        receipt = self.e.record("container", target)
        events = self.e.journal.events
        need(any(x["event"] == "receipt" and x.get("kind") == "container" and x.get("id") == target
                 and x.get("plan") == receipt["plan"] for x in events), "original_201_receipt_required")
        unknown = self.e.uncertain_posts()
        need(len(unknown) == 1 and unknown[0]["api_kind"] == "start" and unknown[0]["http_status"] == 500
             and unknown[0]["unknown_record_persisted"], "other_or_unpersisted_unknown_blocks_reconciliation")
        plan = unknown[0]["plan"]
        need(cleanup.get("unknown_plans") == [plan], "other_unknown_plan_blocks_reconciliation")
        need(any(x["event"] == "plan" and x["sequence"] == plan and x.get("method") == "POST"
                 and x.get("api_kind") == "start" and x.get("intent", {}).get("id") == target for x in events), "start_plan_target_mismatch")
        need(any(x["event"] == "unknown" and x.get("plan") == plan and x.get("http_status") == 500 for x in events), "original_unknown_missing")
        need(any(x["event"] == "case" and x.get("case") == self.case_order[-1] and x.get("terminal") is True
                 and x["sequence"] > plan
                 and x.get("status") == "pass" and x.get("expected_start_failure_id") == target
                 and x.get("case_index") == len(self.case_order) and x.get("total_cases") == len(self.case_order) for x in events), "terminal_case_proof_missing")
        delete_plans = [x["sequence"] for x in events if x["event"] == "plan" and x["sequence"] > plan
                        and x.get("method") == "DELETE" and x.get("intent", {}).get("id") == target]
        replies = [x["sequence"] for x in events if x["event"] == "reply" and x.get("plan") in delete_plans and x.get("http_status") == 204]
        need(replies and any(x["event"] == "container_absent" and x.get("id") == target
             and x.get("verified_404") is True and x["sequence"] > min(replies) for x in events), "own_delete_and_persisted_404_required")
        status, _ = self.e.request("GET", "/containers/" + target + "/json")
        need(status == 404, "final_exact_id_not_absent")
        need(not any(x["event"] == "EXPECTED_START_FAILURE_RECONCILED" for x in events), "reconciliation_already_recorded")
        result = {"status": "EXPECTED_START_FAILURE_RECONCILED", "case": self.case_order[-1], "plan": plan,
                  "container_id": target, "receipt_plan": receipt["plan"], "exact_id_404_persisted": True,
                  "original_unknown_preserved": True, "allocation_still_closed": True}
        self.e.journal.append("EXPECTED_START_FAILURE_RECONCILED", **result)
        return result

    def run(self):
        need(not self._run_started, "suite_already_attempted")
        self._run_started = True
        need(self.case_order == execution_case_order(self.port_conflict_order), "case_order_changed")
        def positive():
            s, _, _ = self.session()
            result = self.outcome(s)
            item = self.e.inspect(result.created_id)
            need(item["Config"]["User"] == "fixtureapp" and len(item["NetworkSettings"]["Networks"]) == 2, "profile_not_preserved")
            need(item["Mounts"][0]["Propagation"] == "rprivate" and item["Mounts"][0]["RW"] is False, "rprivate_required")
            self.e.remove(result.created_id)
        self.run_case(CASES[0], positive)
        def wrong_uid():
            s, _, _ = self.session(identity=(10002, 10001))
            r = self.outcome(s, "identity_mismatch")
            self.e.remove(r.created_id)
        self.run_case(CASES[1], wrong_uid)
        def replaced_before_create():
            s, hook, _ = self.session()
            self.replace_file()
            count = len(self.e.records)
            self.outcome(s, "resource_changed")
            need(not hook.starts and len(self.e.records) == count, "changed_source_mutated_docker")
        self.run_case(CASES[2], replaced_before_create)
        def replaced_before_start():
            hook = Hook(self.e, after_create=lambda _: self.replace_file())
            s, _, _ = self.session(hook=hook)
            r = self.outcome(s, "bind_source_changed")
            need(not hook.starts, "changed_source_started")
            self.e.remove(r.created_id)
        self.run_case(CASES[3], replaced_before_start)
        def symlink():
            self.trusted.unlink("synthetic-empty")
            self.trusted.symlink("synthetic-missing-file", "synthetic-empty")
            try:
                try:
                    self.probe.describe(self.e.owned_file)
                except p.RecoveryBlocked as error:
                    need(str(error) == "bind_symlink_rejected", "wrong_symlink_rejection")
                else:
                    raise Blocked("symlink_accepted")
            finally:
                self.trusted.unlink("synthetic-empty")
                self.empty_file()
        self.run_case(CASES[4], symlink)
        def options():
            try:
                p.validate_bind_strings({"Binds": [self.e.owned_file + ":/fixture-config:ro,rprivate"]})
            except p.RecoveryBlocked:
                return
            raise Blocked("unsupported_option_accepted")
        self.run_case(CASES[5], options)
        def competitor_port():
            s, _, _ = self.session()
            holder = self.e.create(self.e.name("port-holder"), self.port_body(self.port))
            self.e.expect("POST", "/containers/" + holder + "/start")
            before = self.e.record("container", holder)["static_sha256"]
            r = self.outcome(s, "start_failed")
            need(r.http_status == 500, "expected_terminal_start_500_required")
            need(self.e.inspect(holder)["State"]["Running"] and self.e.record("container", holder)["static_sha256"] == before, "port_holder_changed")
            self.e.remove(r.created_id)
            self.e.remove(holder)
            self._expected_start_failure_id = r.created_id
        def recovery_port():
            s, _, _ = self.session()
            r = self.outcome(s)
            holder = self.e.create(self.e.name("late-port-holder"), self.port_body(self.port))
            status, _ = self.e.request("POST", "/containers/" + holder + "/start")
            need(status == 500 and not self.e.inspect(holder)["State"]["Running"] and self.e.inspect(r.created_id)["State"]["Running"], "late_port_claim_not_blocked")
            self.e.remove(holder)
            self.e.remove(r.created_id)
            self._expected_start_failure_id = holder
        def competitor_name():
            s, hook, target = self.session()
            actor = self.e.create(target, self.base())
            self.outcome(s, "name_occupied")
            need(not hook.starts and not self.e.inspect(actor)["State"]["Running"], "name_competitor_started")
            self.e.remove(actor)
        self.run_case(CASES[8], competitor_name)
        def recovery_name():
            s, _, target = self.session()
            r = self.outcome(s)
            body = self.base()
            self.e.permit_container(target, body)
            status, _ = self.e.request("POST", "/containers/create?" + urllib.parse.urlencode({"name": target}), body)
            need(status == 409, "duplicate_name_not_blocked")
            self.e.remove(r.created_id)
        self.run_case(CASES[9], recovery_name)
        def concurrent_name():
            target, body = self.e.name("atomic-race"), self.base()
            self.e.permit_container(target, body)
            barrier, outputs = threading.Barrier(2), []
            def actor():
                try:
                    barrier.wait(timeout=5)
                    outputs.append(self.e.request("POST", "/containers/create?" + urllib.parse.urlencode({"name": target}), body))
                except BaseException:
                    outputs.append((0, None))
            self._set_case_stage("CONCURRENT_CREATE")
            threads = [threading.Thread(target=actor) for _ in range(2)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(12)
            need(all(not t.is_alive() for t in threads) and sorted(x[0] for x in outputs) == [201, 409], "concurrent_name_claim_failed")
            for status, payload in outputs:
                if status == 201:
                    self.e.remove(payload["Id"])
        self.run_case(CASES[10], concurrent_name)
        def concurrent_session():
            s, _, _ = self.session()
            outputs = []
            def recover():
                outputs.append(s.recover(health_timeout=12))
            self._set_case_stage("RECOVER")
            threads = [threading.Thread(target=recover) for _ in range(2)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(20)
            need(all(not t.is_alive() for t in threads) and sorted(x.status for x in outputs) == ["already_attempted", "healthy"], "same_session_not_single_attempt")
            self.e.remove(next(x.created_id for x in outputs if x.status == "healthy"))
        self.run_case(CASES[11], concurrent_session)
        def restart():
            body = self.profile(bind=False, ports=False)
            marker = "/tmp/" + self.e.run + ".once"
            ready = "/tmp/" + self.e.run + ".ready"
            body["HostConfig"]["ReadonlyRootfs"] = False
            body["HostConfig"]["RestartPolicy"] = {"Name": "unless-stopped", "MaximumRetryCount": 0}
            body["Cmd"] = ["sh", "-c", "if test ! -f " + marker + "; then touch " + marker + "; sleep 11; exit 7; fi; touch " + ready + "; sleep 300"]
            body["Healthcheck"]["Test"] = ["CMD-SHELL", "test -f " + ready]
            s, _, _ = self.session(body)
            r = self.outcome(s, timeout=15)
            need(self.e.inspect(r.created_id)["RestartCount"] == 1, "genuine_restart_not_proved")
            self.e.remove(r.created_id)
        self.run_case(CASES[12], restart)
        def unhealthy():
            body = self.profile(bind=False, ports=False)
            body["HostConfig"]["RestartPolicy"] = {"Name": "unless-stopped", "MaximumRetryCount": 0}
            body["Healthcheck"]["Test"] = ["CMD-SHELL", "exit 1"]
            s, _, _ = self.session(body)
            started = time.monotonic()
            r = self.outcome(s, "unhealthy", timeout=15)
            need(time.monotonic() - started >= 14.8, "fixed_health_window_shortened")
            self.e.remove(r.created_id)
        self.run_case(CASES[13], unhealthy)
        self.run_case(self.case_order[-1], competitor_port if self.port_conflict_order == "competitor-first" else recovery_port)


def write_result(directory, name, result):
    need(isinstance(directory, TrustedDirectory), "live_result_requires_trusted_directory")
    fd = directory.open_file(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    data = canonical(result) + b"\n"
    try:
        offset = 0
        while offset < len(data):
            written = os.write(fd, data[offset:])
            need(written > 0, "result_write_failed")
            offset += written
        os.fsync(fd)
    finally:
        os.close(fd)
    directory.sync()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--dry-run", action="store_true")
    group.add_argument("--execute-local", action="store_true")
    parser.add_argument("--cached-alpine-id")
    parser.add_argument("--isolated-daemon-attested", action="store_true")
    parser.add_argument("--min-available-mib", type=int, default=384)
    parser.add_argument("--port-conflict-order", choices=("competitor-first", "recovery-first"))
    args = parser.parse_args(argv)
    admission = MemoryAdmission(args.min_available_mib)
    if not args.execute_local:
        print(json.dumps({"mode": "dry-run", "docker_connections": 0, "mutations": 0,
                          "required_runtime": "Linux Python3.6 / Engine26.1.3 API1.45",
                          "compose_used": False, "cases": CASES,
                          "min_available_mib": admission.minimum_mib,
                          "port_conflict_order": args.port_conflict_order,
                          "selected_execution_cases": execution_case_order(args.port_conflict_order) if args.port_conflict_order else None,
                          "known_limit": "No adversarial administrator/ID-as-name atomicity guarantee"}, indent=2))
        return 0
    need(args.isolated_daemon_attested, "isolated_nonadversarial_daemon_attestation_required")
    need(isinstance(args.cached_alpine_id, str) and IMAGE_RE.fullmatch(args.cached_alpine_id), "explicit_cached_alpine_id_required")
    execution_case_order(args.port_conflict_order)
    need(sys.platform.startswith("linux") and sys.version_info[:2] == (3, 6), "linux_python36_required")
    admission.recorder = lambda record: print("Jovi, " + json.dumps({"memory_admission": record}, sort_keys=True), flush=True)
    admission.check("initial")
    # Construct transport only here. Wrong OS/version/env gates precede socket connect.
    transport = UnixTransport()
    root_trust = no_symlink_directory(ROOT)
    runs_trust = directory = journal = receipt_directory = receipt_store = None
    run = "ml26" + uuid.uuid4().hex
    try:
        runs_trust = root_trust.mkdir_child("runs", reuse=True)
        directory = runs_trust.mkdir_child(run)
        journal = Journal(directory, run)
        for record in admission.events:
            journal.append("memory_admission", **record)
        admission.recorder = lambda record: journal.append("memory_admission", **record)
        receipt_store, receipt_directory = make_native_receipt_store(directory)
        engine = GuardedEngine(transport, journal, run, args.cached_alpine_id,
                               directory.path + "/synthetic-empty", trusted_directory=directory, memory_admission=admission)
        suite, error = LiveSuite(engine, directory, receipt_store=receipt_store, port_conflict_order=args.port_conflict_order), None
        reconciliation = None
        cleanup = None
        try:
            print("Jovi, setup synthetic-only Engine26 fixture", flush=True)
            suite.setup()
            suite.run()
        except BaseException:
            # No traceback or raw daemon error: those could contain paths/config.
            error = "candidate_execution_failed_review_journal"
        finally:
            try:
                cleanup = engine.cleanup()
            except BaseException:
                cleanup = {"cleanup_failed": True, "manual_receipt_review_required": True}
            if error is None:
                try:
                    reconciliation = suite.reconcile_expected_start_failure(cleanup)
                except BaseException:
                    error = "expected_start_failure_reconciliation_blocked"
            result = {"run": run, "mode": "local-synthetic-live", "cases": suite.results,
                      "error": error, "cleanup": cleanup, "production_access": False,
                      "pulls": False, "compose_used": False, "application_acceptance": False}
            result["port_conflict_order"] = args.port_conflict_order
            result["expected_start_failure_reconciliation"] = reconciliation
            result["unknown_history_preserved"] = True
            result["result_status"] = reconciliation["status"] if reconciliation and error is None else "BLOCKED"
            result["memory_admission"] = {"threshold_kib": admission.threshold_kib,
                "minimum_observed_kib": admission.minimum_observed_kib,
                "checks": len(admission.events), "refusals": sum(not x["allowed"] for x in admission.events)}
            write_result(directory, "result.json", result)
            write_result(directory, "health-observations.json", {"observations": engine.observations, "health_output_persisted": False})
        print(json.dumps({"run": run, "passed_cases": len(suite.results), "expected_cases": len(suite.case_order),
                          "result_status": result["result_status"], "unknown_history_preserved": True,
                          "reconciliation": reconciliation, "error": error, "cleanup": cleanup}, indent=2))
        return 0 if error is None and reconciliation is not None and not cleanup.get("retained") and not cleanup.get("cleanup_failed") else 1
    finally:
        try:
            if receipt_store:
                receipt_store.close()
        finally:
            try:
                if journal:
                    journal.close()
            finally:
                for trusted in (receipt_directory, directory, runs_trust, root_trust):
                    if trusted:
                        trusted.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Blocked, p.RecoveryBlocked) as error:
        print("Jovi, blocked: " + str(error), file=sys.stderr)
        sys.exit(2)
