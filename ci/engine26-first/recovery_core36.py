"""Bounded LOCAL-only recovery prototype, API v1.45 on Engine 26.1.3 (UNEXECUTED candidate).

Private snapshots stay in memory. No pull, data-resource create, name adoption,
implicit retry, or remote-host support. Ordinary-name concurrency only: Docker's
ID-or-name resolver still permits deliberate deleted-ID-as-name substitution.
"""
import copy
import datetime
import http.client
import io
import ipaddress
import os
import queue
import re
import stat
import threading
import time
import urllib.parse
import uuid

from receipt_journal import ReceiptStore, verified_ack

EXPECTED_ENGINE_VERSION = "26.1.3"


class RecoveryBlocked(Exception):
    """A constant reason code; never includes request bodies or daemon messages."""


def require(condition, reason):
    if not condition:
        raise RecoveryBlocked(reason)


def full_id(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


class LocalEngine:
    """No default transport: the reviewed guarded wrapper is mandatory."""
    def request(self, method, path, body=None):
        raise RecoveryBlocked("guarded_wrapper_required")


CONFIG_KEYS = set("""Hostname Domainname User AttachStdin AttachStdout AttachStderr ExposedPorts Tty
OpenStdin StdinOnce Env Cmd Healthcheck ArgsEscaped Image Volumes WorkingDir Entrypoint NetworkDisabled
MacAddress OnBuild Labels StopSignal StopTimeout Shell""".split())
HEALTH_KEYS = {"Test", "Interval", "Timeout", "Retries", "StartPeriod", "StartInterval"}
HOST_DEFAULTS = {
    "ContainerIDFile": "", "AutoRemove": False,
    "VolumeDriver": "", "VolumesFrom": None, "ConsoleSize": [0, 0], "CapAdd": None,
    "CgroupnsMode": "private", "Dns": None, "DnsOptions": None, "DnsSearch": None,
    "ExtraHosts": None, "GroupAdd": None, "IpcMode": "private", "Cgroup": "", "Links": None,
    "OomScoreAdj": 0, "PidMode": "", "Privileged": False, "PublishAllPorts": False,
    "UTSMode": "", "UsernsMode": "", "Runtime": "runc", "Isolation": "", "CpuShares": 0,
    "NanoCpus": 0, "CgroupParent": "", "BlkioWeight": 0, "BlkioWeightDevice": None,
    "BlkioDeviceReadBps": None, "BlkioDeviceWriteBps": None, "BlkioDeviceReadIOps": None,
    "BlkioDeviceWriteIOps": None, "CpuPeriod": 0, "CpuQuota": 0, "CpuRealtimePeriod": 0,
    "CpuRealtimeRuntime": 0, "CpusetCpus": "", "CpusetMems": "", "Devices": None,
    "DeviceCgroupRules": None, "DeviceRequests": None, "MemoryReservation": 0,
    "MemorySwappiness": None, "Ulimits": None, "CpuCount": 0,
    "CpuPercent": 0, "IOMaximumIOps": 0, "IOMaximumBandwidth": 0,
}
HOST_COPY = {"LogConfig", "NetworkMode", "RestartPolicy", "ReadonlyRootfs", "SecurityOpt",
             "Memory", "MemorySwap", "OomKillDisable", "PidsLimit", "ShmSize", "CapDrop", "Mounts", "Binds", "PortBindings", "MaskedPaths", "ReadonlyPaths"}
ENDPOINT_KEYS = set("""IPAMConfig Links Aliases DriverOpts GwPriority NetworkID EndpointID Gateway
IPAddress MacAddress IPPrefixLen IPv6Gateway GlobalIPv6Address GlobalIPv6PrefixLen DNSNames""".split())
NETWORK_KEYS = set("""Name Id Created Scope Driver EnableIPv4 EnableIPv6 IPAM Internal Attachable
Ingress ConfigFrom ConfigOnly Containers Options Labels Peers Services""".split())
VOLUME_KEYS = {"CreatedAt", "Driver", "Labels", "Mountpoint", "Name", "Options", "Scope", "Status"}
MOUNT_KEYS = {"Type", "Name", "Source", "Destination", "Driver", "Mode", "RW", "Propagation"}


class Outcome:
    __slots__ = ("status", "created_id", "http_status", "successful_health_probes")
    def __init__(self, status, created_id=None, http_status=None, successful_health_probes=0):
        self.status = status
        self.created_id = created_id
        self.http_status = http_status
        self.successful_health_probes = successful_health_probes


class _Plan:
    def __init__(self, target, image, config, host, mounts, endpoints, networks, volumes, identity, binds):
        self.target, self.image, self.config, self.host = target, image, config, host
        self.mounts, self.endpoints, self.networks = mounts, endpoints, networks
        self.volumes, self.identity, self.binds = volumes, identity, binds

    def body(self):
        body = copy.deepcopy(self.config)
        body.update(HostConfig=copy.deepcopy(self.host),
                    NetworkingConfig={"EndpointsConfig": copy.deepcopy(self.endpoints)})
        return body


def get(engine, path):
    status, result = engine.request("GET", path)
    require(status == 200 and isinstance(result, dict), "resource_unavailable")
    return result


def network_identity(item):
    require(not set(item) - NETWORK_KEYS, "unknown_network_field")
    require(item.get("Driver") == "bridge" and item.get("Scope") == "local", "unsupported_network_driver")
    require(not item.get("EnableIPv6") and not item.get("Ingress") and not item.get("ConfigOnly"), "unsupported_network_mode")
    require(not item.get("Peers") and not item.get("Services"), "unsupported_network_topology")
    return {key: copy.deepcopy(value) for key, value in item.items() if key != "Containers"}


def volume_identity(item):
    require(not set(item) - VOLUME_KEYS, "unknown_volume_field")
    require(item.get("Driver") == "local" and item.get("Scope") == "local"
            and not item.get("Options") and not item.get("Status"), "unsupported_volume_driver")
    return copy.deepcopy(item)


def endpoint_intent(endpoint, generated_mac_attested):
    require(generated_mac_attested is True, "generated_mac_provenance_required")
    require(not set(endpoint) - ENDPOINT_KEYS, "unknown_endpoint_field")
    require(not endpoint.get("Links") and not endpoint.get("DriverOpts")
            and not endpoint.get("GwPriority"), "unsupported_endpoint_options")
    require(not endpoint.get("GlobalIPv6Address"), "unsupported_ipv6")
    ipam = endpoint.get("IPAMConfig")
    if ipam:
        require(not set(ipam) - {"IPv4Address", "IPv6Address", "LinkLocalIPs"}, "unknown_ipam_field")
        require(not ipam.get("IPv6Address") and not ipam.get("LinkLocalIPs") and ipam.get("IPv4Address"), "unsupported_ipam")
        try:
            require(ipaddress.ip_address(ipam["IPv4Address"]).version == 4, "unsupported_ipam")
        except ValueError:
            raise RecoveryBlocked("unsupported_ipam") from None
    intent = {"Aliases": copy.deepcopy(endpoint.get("Aliases"))}
    if ipam is not None:
        intent["IPAMConfig"] = copy.deepcopy(ipam)
    return intent


def validate_config(config, host):
    require(not set(config) - CONFIG_KEYS, "unknown_config_field")
    require(not set(host) - (set(HOST_DEFAULTS) | HOST_COPY), "unknown_hostconfig_field")
    for key in ("AttachStdin", "OpenStdin", "StdinOnce", "Tty", "ArgsEscaped", "NetworkDisabled"):
        require(not config.get(key), "unsupported_interactive_or_network_config")
    require(not config.get("Volumes") and not config.get("MacAddress") and not config.get("OnBuild"), "unsupported_config_intent")
    require(re.fullmatch(r"(?:(?:[0-9]+|[A-Za-z_][A-Za-z0-9_.-]*)(?::(?:[0-9]+|[A-Za-z_][A-Za-z0-9_.-]*))?)?", config.get("User", "")) is not None, "unsupported_user_syntax")
    require(isinstance(config.get("Env"), list) and all(isinstance(value, str) for value in config["Env"]), "invalid_env")
    require(isinstance(config.get("Labels"), dict), "invalid_labels")
    health = config.get("Healthcheck", {})
    require(not set(health) - HEALTH_KEYS, "unknown_health_field")
    require(health.get("Test") and health["Test"][0] in ("CMD", "CMD-SHELL"), "complete_healthcheck_required")
    for key, default in HOST_DEFAULTS.items():
        require(key not in host or host[key] == default, "unsupported_hostconfig_value")
    require(host.get("LogConfig") == {"Type": "json-file", "Config": {}}, "unsupported_log_driver")
    require(host.get("CapDrop") in (None, ["ALL"]), "unsupported_capabilities")
    require(host.get("SecurityOpt") in (None, ["no-new-privileges"], ["no-new-privileges:true"],
                                        ["no-new-privileges=true"]), "unsupported_security_options")
    validate_bind_strings(host)
    bindings = host.get("PortBindings", {})
    if bindings is None:
        bindings = {}  # Engine26 inspect null means no published ports.
    require(isinstance(bindings, dict) and len(bindings) <= 1, "unsupported_port_binding_count")
    for port, items in bindings.items():
        require(re.fullmatch(r"[0-9]+/(?:tcp|udp)", port) is not None
                and 1 <= int(port.split("/")[0]) <= 65535 and len(items) == 1, "unsupported_port_binding")
        item = items[0]
        require(set(item) == {"HostIp", "HostPort"} and isinstance(item["HostPort"], str)
                and item["HostPort"].isdigit() and str(int(item["HostPort"])) == item["HostPort"]
                and 1 <= int(item["HostPort"]) <= 65535, "fixed_host_port_required")
        try:
            require(ipaddress.ip_address(item["HostIp"]).is_loopback, "loopback_port_required")
        except ValueError:
            raise RecoveryBlocked("loopback_port_required") from None
    require(isinstance(host.get("Memory"), int) and 0 <= host["Memory"] <= 1073741824, "unsupported_memory")
    require(isinstance(host.get("MemorySwap"), int) and 0 <= host["MemorySwap"] <= 2147483648, "unsupported_memory_swap")
    require(host.get("OomKillDisable") in (None, False), "unsupported_oom_policy")
    require(host.get("PidsLimit") is None or 1 <= host["PidsLimit"] <= 4096, "unsupported_pids_limit")
    require(0 < host.get("ShmSize", 0) <= 67108864, "unsupported_shared_memory")
    restart = host.get("RestartPolicy", {})
    require(set(restart) == {"Name", "MaximumRetryCount"}
            and restart["Name"] in ("no", "on-failure", "always", "unless-stopped")
            and isinstance(restart["MaximumRetryCount"], int) and restart["MaximumRetryCount"] >= 0,
            "unsupported_restart_policy")


def validate_mounts(engine, host, mounts):
    volumes = {}
    specs = host.get("Mounts", [])
    require(isinstance(specs, list) and len(specs) == len(mounts), "mount_mapping_incomplete")
    require(len({x.get("Target") for x in specs}) == len(specs), "duplicate_mount_target")
    targets = [x.get("Target", "") for x in specs]
    require(not any(a != b and b.startswith(a.rstrip("/") + "/") for a in targets for b in targets), "nested_mount_order_unsupported")
    for spec in specs:
        require(not set(spec) - {"Type", "Source", "Target", "ReadOnly", "VolumeOptions", "TmpfsOptions"}, "unsupported_mount_field")
        require(isinstance(spec.get("Target"), str) and spec["Target"].startswith("/") and spec["Target"] != "/", "invalid_mount_target")
        actual = [x for x in mounts if x.get("Destination") == spec["Target"]]
        require(len(actual) == 1, "mount_mapping_incomplete")
        actual = actual[0]
        require(not set(actual) - MOUNT_KEYS and actual.get("Type") == spec["Type"], "unknown_or_changed_mount")
        require(actual.get("RW") == (not spec.get("ReadOnly", False))
                and actual.get("Propagation") in ("", "rprivate"), "mount_permissions_changed")
        if spec["Type"] == "volume":
            require(not spec.get("TmpfsOptions") and spec.get("Source"), "unsupported_volume_mount")
            options = spec.get("VolumeOptions", {})
            require(not set(options) - {"NoCopy"}, "unsupported_volume_options")
            item = volume_identity(get(engine, "/volumes/" + urllib.parse.quote(spec["Source"], safe="")))
            require(actual.get("Name") == item["Name"] == spec["Source"]
                    and actual.get("Source") == item["Mountpoint"]
                    and actual.get("Driver") == item["Driver"], "volume_mount_changed")
            volumes[item["Name"]] = item
        elif spec["Type"] == "tmpfs":
            require(not spec.get("Source") and not spec.get("VolumeOptions"), "unsupported_tmpfs")
            require(not set(spec.get("TmpfsOptions", {})) - {"SizeBytes", "Mode"}, "unsupported_tmpfs_options")
            require(actual.get("Source") == "", "tmpfs_source_changed")
        else:
            raise RecoveryBlocked("unsupported_mount_type")
    return volumes


def posix_path(value):
    require(isinstance(value, str) and value.startswith("/") and value != "/"
            and "\x00" not in value and ":" not in value
            and all(part not in ("", ".", "..") for part in value.split("/")[1:]), "unsupported_bind_path")
    return value


def validate_bind_strings(host):
    binds = host.get("Binds") or []
    require(isinstance(binds, list) and len(binds) <= 1, "one_bind_only")
    require(not binds or not host.get("Mounts"), "mixed_bind_mount_forms_unsupported")
    result = []
    for binding in binds:
        require(isinstance(binding, str) and len(binding.split(":")) == 3, "unsupported_bind_syntax")
        source, target, options = binding.split(":")
        require(options == "ro", "readonly_ro_only_bind_required")
        result.append((posix_path(source), posix_path(target)))
    return result


def exec_output(engine, container_id, command):
    require(full_id(container_id), "full_exec_container_id_required")
    status, created = engine.request("POST", "/containers/" + container_id + "/exec",
        {"Cmd": command, "AttachStdout": True, "AttachStderr": True, "Tty": False})
    require(status == 201 and isinstance(created, dict) and full_id(created.get("Id")), "probe_exec_unavailable")
    exec_id = created["Id"]
    status, data = engine.request("POST", "/exec/" + exec_id + "/start", {"Detach": False, "Tty": False})
    require(status == 200 and isinstance(data, bytes), "probe_exec_unavailable")
    output = bytearray()
    while data:
        require(len(data) >= 8 and data[1:4] == b"\0\0\0", "invalid_probe_stream")
        stream, length = data[0], int.from_bytes(data[4:8], "big")
        require(len(data) >= 8 + length and stream in (1, 2), "invalid_probe_stream")
        require(stream != 2 or length == 0, "probe_failed")
        if stream == 1:
            output.extend(data[8:8 + length])
        data = data[8 + length:]
    result = get(engine, "/exec/" + exec_id + "/json")
    require(result.get("ContainerID") == container_id and not result.get("Running")
            and result.get("ExitCode") == 0 and len(output) <= 16384, "probe_failed")
    return bytes(output)


def verify_identity(engine, container_id, expected):
    output = exec_output(engine, container_id, ["/bin/sh", "-c",
        "id -u; id -g; awk '/^Uid:/{print $2,$3,$4,$5}/^Gid:/{print $2,$3,$4,$5}' /proc/1/status"])
    fields = output.split()
    require(len(fields) == 10 and all(value.isdigit() for value in fields), "identity_unverified")
    values = [int(value) for value in fields]
    uid, gid = expected
    require(values == [uid, gid] + [uid] * 4 + [gid] * 4, "identity_mismatch")


STAT_FORMAT = "%d|%i|%f|%u|%g|%s|%h|%y|%z"


def nanoseconds(value):
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.(\d{9}) ([+-]\d{4})", value)
    require(match is not None, "metadata_nanosecond_precision_required")
    moment = datetime.datetime.strptime(match[1] + " " + match[3], "%Y-%m-%d %H:%M:%S %z")
    return int(moment.timestamp()) * 1000000000 + int(match[2])


def parse_stat(data):
    try:
        fields = data.decode("ascii").strip().split("|")
        require(len(fields) == 9, "metadata_unverified")
        return {"dev": int(fields[0]), "ino": int(fields[1]), "mode": int(fields[2], 16),
                "uid": int(fields[3]), "gid": int(fields[4]), "size": int(fields[5]),
                "nlink": int(fields[6]), "mtime_ns": nanoseconds(fields[7]), "ctime_ns": nanoseconds(fields[8])}
    except (ValueError, UnicodeError):
        raise RecoveryBlocked("metadata_unverified") from None


def mounted_metadata(engine, container_id, target):
    return parse_stat(exec_output(engine, container_id, ["stat", "-c", STAT_FORMAT, "--", posix_path(target)]))


class NativeBindProbe:
    """Metadata only, Linux daemon host only; explicit filesystem attestation.

    This provider is NOT exercised against a production filesystem here.
    Every component is opened with O_NOFOLLOW; no file contents are read.
    """
    def __init__(self, *, daemon_filesystem_attested=False):
        require(daemon_filesystem_attested is True and os.name == "posix"
                and hasattr(os, "O_PATH") and hasattr(os, "O_NOFOLLOW"), "same_host_metadata_provider_required")

    def describe(self, source):
        parts = posix_path(source).split("/")[1:]
        descriptor = os.open("/", os.O_PATH | os.O_DIRECTORY)
        ancestors = []
        try:
            for index, part in enumerate(parts):
                next_descriptor = os.open(part, os.O_PATH | os.O_NOFOLLOW, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = next_descriptor
                value = os.fstat(descriptor)
                require(not stat.S_ISLNK(value.st_mode), "bind_symlink_rejected")
                identity = {"dev": value.st_dev, "ino": value.st_ino, "mode": value.st_mode,
                            "uid": value.st_uid, "gid": value.st_gid}
                if index != len(parts) - 1:
                    require(stat.S_ISDIR(value.st_mode), "bind_parent_not_directory")
                    ancestors.append(identity)
                else:
                    require(stat.S_ISREG(value.st_mode), "bind_source_not_regular_file")
                    identity.update(size=value.st_size, nlink=value.st_nlink,
                                    mtime_ns=value.st_mtime_ns, ctime_ns=value.st_ctime_ns)
                    return {"ancestors": ancestors, "file": identity}
        except OSError:
            raise RecoveryBlocked("bind_source_unavailable") from None
        finally:
            os.close(descriptor)


def capture_binds(host, mounts, provider):
    specs = validate_bind_strings(host)
    if not specs:
        return []
    require(provider is not None and len(mounts) == 1, "bind_metadata_provider_required")
    result = []
    for source, target in specs:
        actual = mounts[0]
        require(set(actual) <= MOUNT_KEYS and actual.get("Type") == "bind" and actual.get("Source") == source
                and actual.get("Destination") == target and actual.get("RW") is False
                and actual.get("Mode") == "ro" and actual.get("Propagation") == "rprivate", "bind_mapping_changed")
        metadata = provider.describe(source)
        require(isinstance(metadata, dict) and set(metadata) == {"ancestors", "file"}
                and stat.S_ISREG(metadata["file"]["mode"]), "bind_source_not_regular_file")
        result.append({"source": source, "target": target, "metadata": metadata})
    return result


def canonical_host(host):
    result = copy.deepcopy(host)
    # This local Engine changes the default false to null after start. Both mean
    # OOM killing is not disabled; true remains unsupported and is never coerced.
    if result.get("OomKillDisable") is None:
        result["OomKillDisable"] = False
    if "PortBindings" in result and result["PortBindings"] is None:
        result["PortBindings"] = {}  # Normalize only explicit null, never other falsy types.
    return result


def canonical_mounts(mounts):
    # Inspect order of disjoint mountpoints is not stable on this Engine.
    return sorted(copy.deepcopy(mounts), key=lambda item: item["Destination"])


def health_time(value):
    # Python 3.6 has no fromisoformat. Preserve all nine Docker fractional digits.
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})", value or "")
    require(match is not None, "health_evidence_incomplete")
    try:
        zone = "+0000" if match[3] == "Z" else match[3].replace(":", "")
        moment = datetime.datetime.strptime(match[1] + zone, "%Y-%m-%dT%H:%M:%S%z")
        return int(moment.timestamp()) * 1000000000 + int((match[2] or "0").ljust(9, "0"))
    except (ValueError, TypeError):
        raise RecoveryBlocked("health_evidence_incomplete") from None


class RecoverySession:
    """One attempt, one creation receipt. No method deletes the captured source."""
    def __init__(self, engine, plan, bind_metadata=None, *, receipt_store=None):
        self._engine, self._plan = engine, plan
        self._created_id = None
        self._attempted = False
        self._lock = threading.Lock()
        self._bind_metadata = bind_metadata
        self._receipt_store = receipt_store
        self._receipt_confirmed = False

    @classmethod
    def capture(cls, engine, source_id, target, *, generated_mac_attested=False, expected_identity=None, bind_metadata=None, receipt_store=None):
        require(isinstance(receipt_store, ReceiptStore), "receipt_store_required")
        require(full_id(source_id), "full_source_id_required")
        require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]+", target) is not None
                and not full_id(target), "invalid_fixed_target_name")
        version = get(engine, "/version")
        require(version.get("Version") == EXPECTED_ENGINE_VERSION and version.get("Os") == "linux", "local_runtime_not_validated")
        source = get(engine, "/containers/" + source_id + "/json")
        require(source.get("Id") == source_id and source.get("Name") == "/" + target, "source_identity_changed")
        config, host = copy.deepcopy(source["Config"]), copy.deepcopy(source["HostConfig"])
        validate_config(config, host)
        if expected_identity is None:
            user = config.get("User", "")
            require(not user or re.fullmatch(r"[0-9]+:[0-9]+", user) is not None, "expected_uid_gid_required")
            expected_identity = tuple(int(value) for value in (user or "0:0").split(":"))
        require(isinstance(expected_identity, tuple) and len(expected_identity) == 2
                and all(type(value) is int and 0 <= value < 2**32 for value in expected_identity), "expected_uid_gid_required")
        host = canonical_host(host)
        image = source["Image"]
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", image) is not None, "immutable_image_required")
        require(get(engine, "/images/" + image + "/json").get("Id") == image, "cached_image_missing")
        config["Image"] = image  # Pin content; a source tag string is not an image identity.
        require(not host.get("Binds") or (source["State"]["Running"] and not source["State"].get("Restarting")), "running_bind_snapshot_required")
        binds = capture_binds(host, source["Mounts"], bind_metadata)
        volumes = {} if binds else validate_mounts(engine, host, source["Mounts"])
        endpoints, networks = {}, {}
        observed = source["NetworkSettings"]["Networks"]
        require(1 <= len(observed) <= 2, "one_or_two_bridge_networks_required")
        for name, endpoint in observed.items():
            network_id = endpoint.get("NetworkID")
            if not network_id:
                require(source["State"]["Status"] == "created" and not endpoint.get("EndpointID")
                        and not endpoint.get("IPAddress"), "unresolved_existing_endpoint")
            item = network_identity(get(engine, "/networks/" + urllib.parse.quote(network_id or name, safe="")))
            require(full_id(item.get("Id")) and name in (item["Name"], item["Id"])
                    and (not network_id or item["Id"] == network_id), "network_identity_changed")
            network_id = item["Id"]
            endpoints[network_id] = endpoint_intent(endpoint, generated_mac_attested)
            networks[network_id] = item
        primary = [network_id for network_id, item in networks.items()
                   if host["NetworkMode"] in (network_id, item["Name"])]
        require(len(primary) == 1, "network_mode_mapping_incomplete")
        host["NetworkMode"] = primary[0]  # Use captured resource ID instead of re-resolving a name.
        return cls(engine, _Plan(target, image, config, host, canonical_mounts(source["Mounts"]), endpoints, networks, volumes,
                                expected_identity, binds), bind_metadata, receipt_store=receipt_store)

    def _resources_match(self):
        require(get(self._engine, "/images/" + self._plan.image + "/json").get("Id") == self._plan.image, "resource_changed")
        for resource_id, expected in self._plan.networks.items():
            require(network_identity(get(self._engine, "/networks/" + resource_id)) == expected, "resource_changed")
        for name, expected in self._plan.volumes.items():
            require(volume_identity(get(self._engine, "/volumes/" + urllib.parse.quote(name, safe=""))) == expected, "resource_changed")
        for binding in self._plan.binds:
            require(self._bind_metadata.describe(binding["source"]) == binding["metadata"], "bind_source_changed")

    def _inspect_created(self, *, verify_network=True, expected_id=None):
        container_id = expected_id or self._created_id
        status, item = self._engine.request("GET", "/containers/" + container_id + "/json")
        if status == 404:
            return None
        require(status == 200 and item.get("Id") == container_id, "created_identity_not_exact")
        require(item.get("Name") == "/" + self._plan.target, "created_target_renamed")
        require(item["Config"] == self._plan.config and canonical_host(item["HostConfig"]) == self._plan.host
                and canonical_mounts(item["Mounts"]) == self._plan.mounts, "created_configuration_changed")
        if not verify_network:
            return item
        endpoints = {}
        for name, endpoint in item["NetworkSettings"]["Networks"].items():
            network_id = endpoint.get("NetworkID")
            if not network_id:
                require(item["State"]["Status"] == "created" and not endpoint.get("EndpointID")
                        and not endpoint.get("IPAddress"), "unresolved_created_endpoint")
                matches = [resource_id for resource_id, network in self._plan.networks.items()
                           if name in (network["Name"], resource_id)]
                require(len(matches) == 1, "created_network_intent_changed")
                network_id = matches[0]
            require(network_id not in endpoints, "duplicate_created_network")
            endpoints[network_id] = endpoint_intent(endpoint, True)
        require(endpoints == self._plan.endpoints, "created_network_intent_changed")
        return item

    def recover(self, *, health_timeout=10):
        with self._lock:
            if self._attempted:
                return Outcome("already_attempted", self._created_id)
            self._attempted = True
        require(.05 <= health_timeout <= 60, "invalid_health_deadline")
        if not isinstance(self._receipt_store, ReceiptStore):
            return Outcome("receipt_store_required")
        try:
            self._resources_match()
        except Exception:
            return Outcome("resource_changed")
        planned = {"schema": 1, "phase": "planned", "attempt_id": uuid.uuid4().hex,
                   "target_name": self._plan.target, "ownership_token": uuid.uuid4().hex}
        try:
            if not verified_ack(planned, self._receipt_store.write_plan(planned.copy())):
                return Outcome("plan_not_durable")
        except Exception:
            return Outcome("plan_not_durable")
        try:
            status, result = self._engine.request("POST", "/containers/create?" +
                urllib.parse.urlencode({"name": self._plan.target}), self._plan.body())
        except Exception:
            return Outcome("create_outcome_unknown")  # Never retry or discover/adopt by name.
        if status == 409:
            return Outcome("name_occupied", http_status=409)
        if status != 201:
            return Outcome("create_failed", http_status=status)
        if not isinstance(result, dict) or not full_id(result.get("Id")):
            return Outcome("create_outcome_unknown")
        observed_id = result["Id"]
        try:
            if self._inspect_created(expected_id=observed_id) is None:
                return Outcome("created_container_missing")
        except Exception:
            return Outcome("created_identity_unconfirmed")
        created = dict(planned, phase="created", container_id=observed_id)
        try:
            if not verified_ack(created, self._receipt_store.write_created(created.copy())):
                return Outcome("receipt_not_durable")
        except Exception:
            return Outcome("receipt_not_durable")
        self._created_id = observed_id
        self._receipt_confirmed = True
        if result.get("Warnings"):
            return Outcome("create_warning", self._created_id)
        try:
            if self._inspect_created() is None:
                return Outcome("created_container_missing", self._created_id)
            self._resources_match()
            status, _ = self._engine.request("POST", "/containers/" + self._created_id + "/start")
            if status not in (204, 304):
                return Outcome("start_failed", self._created_id, status)
            deadline = time.monotonic() + health_timeout
            stable_generation = None
            last_probe_end = None
            successful_probes = 0
            last_health = None
            while time.monotonic() < deadline:
                item = self._inspect_created()
                if item is None:
                    return Outcome("created_container_missing", self._created_id)
                state = item["State"]
                health = state.get("Health", {}).get("Status")
                last_health = health
                if state["Running"] and not state.get("Restarting") and health == "healthy":
                    # Require three DISTINCT successful Docker health probes,
                    # not repeated reads of one successful result. All three
                    # must share PID, start generation and complete network state.
                    generation = (state["Pid"], state["StartedAt"], item["RestartCount"],
                                  copy.deepcopy(item["NetworkSettings"]))
                    require(state["Pid"] > 0, "health_evidence_incomplete")
                    if generation != stable_generation:
                        stable_generation, successful_probes, last_probe_end = generation, 0, None
                    logs = state.get("Health", {}).get("Log", [])
                    require(logs and logs[-1].get("ExitCode") == 0, "health_evidence_incomplete")
                    latest = logs[-1]
                    require(health_time(latest["Start"]) >= health_time(state["StartedAt"])
                            and health_time(latest["End"]) >= health_time(latest["Start"]), "health_evidence_incomplete")
                    if latest["End"] != last_probe_end:
                        if last_probe_end is not None:
                            positions = [index for index, log in enumerate(logs) if log.get("End") == last_probe_end]
                            if not positions or any(log.get("ExitCode") != 0 for log in logs[positions[0] + 1:]):
                                successful_probes = 0
                        self._resources_match()
                        verify_identity(self._engine, self._created_id, self._plan.identity)
                        for binding in self._plan.binds:
                            require(mounted_metadata(self._engine, self._created_id, binding["target"])
                                    == binding["metadata"]["file"], "mounted_bind_source_changed")
                        successful_probes += 1
                        last_probe_end = latest["End"]
                        if successful_probes >= 3:
                            # Resource checks can take time; require a fresh final
                            # observation of the same generation/configuration.
                            final = self._inspect_created()
                            if final is None:
                                return Outcome("created_container_missing", self._created_id)
                            final_state = final["State"]
                            final_generation = (final_state["Pid"], final_state["StartedAt"], final["RestartCount"],
                                                final["NetworkSettings"])
                            final_logs = final_state.get("Health", {}).get("Log", [])
                            final_positions = [index for index, log in enumerate(final_logs) if log.get("End") == last_probe_end]
                            no_intervening_failure = (bool(final_positions)
                                and all(log.get("ExitCode") == 0 for log in final_logs[final_positions[0]:]))
                            if (time.monotonic() < deadline and final_state["Running"]
                                    and not final_state.get("Restarting")
                                    and final_state.get("Health", {}).get("Status") == "healthy"
                                    and final_generation == stable_generation and no_intervening_failure):
                                return Outcome("healthy", self._created_id, successful_health_probes=successful_probes)
                            stable_generation, successful_probes, last_probe_end = None, 0, None
                else:
                    # Docker may temporarily expose unhealthy while restarting
                    # with PID=0. It is not readiness, and is observed until the
                    # same fixed deadline; every interruption resets the streak.
                    stable_generation, successful_probes, last_probe_end = None, 0, None
                if state.get("Dead") or (state["Status"] == "exited" and self._plan.host["RestartPolicy"]["Name"] == "no"):
                    return Outcome("exited", self._created_id)
                time.sleep(.1)
            return Outcome("unhealthy" if last_health == "unhealthy" else "health_timeout", self._created_id)
        except RecoveryBlocked as error:
            return Outcome(str(error), self._created_id)
        except Exception:
            return Outcome("operation_outcome_unknown", self._created_id)

    def cleanup(self):
        """Production default: no Docker mutation, including after journal failure.

        Full IDs are still ID-or-name references. A separate inspect cannot
        eliminate an administrator's alias TOCTOU, so automatic delete is off.
        """
        return "automatic_delete_disabled"

    def cleanup_for_test(self):
        """Explicit isolated-fixture opt-in; NOT safe against hostile alias races."""
        if not self._receipt_confirmed:
            return "no_receipt"
        if self._created_id is None:
            return "no_receipt"
        try:
            item = self._inspect_created(verify_network=False)
            if item is None:
                return "absent"
            # v=0 preserves original named volumes. Never clean networks/volumes.
            status, _ = self._engine.request("DELETE", "/containers/" + self._created_id + "?force=1&v=0")
            if status == 204:
                self._created_id = None
                return "removed"
            return "cleanup_failed"
        except Exception:
            return "cleanup_blocked_identity_or_configuration"
