"""GitHub disposable-VM orchestrator. Import is inert; no local execution mode.

No Docker enumeration, name adoption, host socket mount or automatic rerun.
The outer daemon is infrastructure owned by this job's fresh GitHub VM.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
import uuid

from verify_evidence import Rejected, load_verify, require

HASHES = {
    "engine26_candidate.py": "cbc2bccce4dd40f11d19ace398849fb4a5608060c545e5f09bcb59d00c940023",
    "recovery_core36.py": "d841e776a565c38645294d50be2d89d11fecde092b2d26eaf2388a79ec69b052",
    "receipt_journal.py": "47c8a9644204d9927b07d74ae4808b1fce930196d178210ab8135f24fb858eba",
}
REFS = {
    "dind": "docker:26.1.3-dind@sha256:f714f2093716175aaa3c3838ed85ee26129b9b7cf5f7da01214f82fcaf967dcc",
    "python": "python:3.6.8-slim-stretch@sha256:342a794da4cb1fd4ecd4b7636544697970daf850a131d8adc334c8719d8ecf44",
    "alpine": "alpine:3.20.3@sha256:029a752048e32e843bd6defe3841186fb8d19a28dae8ec287f433bb9d6d1ad85",
}
FULL_ID = re.compile(r"[0-9a-f]{64}\Z")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}\Z")
HOST_ENV = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME": "/root", "DOCKER_CONFIG": "/root/ml26-no-credentials",
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_SYSTEM": "/dev/null", "LANG": "C.UTF-8"}
TMPFS = "/root/fixture:rw,nosuid,nodev,noexec,size=1m,mode=0700,uid=0,gid=0"
MAX_EVIDENCE_BYTES = 16 * 1024 * 1024


class FinalizerTimeout(BaseException):
    """Bypasses inner per-resource recovery; the whole finalizer is out of time."""


def identity(meta):
    return meta.st_dev, meta.st_ino, meta.st_mode, meta.st_uid, meta.st_gid, meta.st_nlink


def validate_export_metadata(meta):
    require(stat.S_ISREG(meta.st_mode) and meta.st_uid == 0 and meta.st_nlink == 1 and
            not meta.st_mode & 0o022 and 0 <= meta.st_size <= MAX_EVIDENCE_BYTES,
            "evidence_file_type_or_size")


@contextmanager
def trusted_directory(path):
    """Pin every root-owned directory component; never follow a symlink."""
    require(path.is_absolute() and all(p not in ("", ".", "..") for p in path.parts[1:]),
            "canonical_export_parent_required")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    chain = []
    def verify():
        for index, (name, fd, expected) in enumerate(chain):
            meta = os.fstat(fd)
            require(identity(meta) == expected and stat.S_ISDIR(meta.st_mode) and meta.st_uid == 0 and
                    not meta.st_mode & 0o022, "export_directory_changed")
            if index:
                named = os.stat(name, dir_fd=chain[index-1][1], follow_symlinks=False)
                require(identity(named) == expected, "export_directory_path_replaced")
    try:
        fd = os.open("/", flags)
        chain.append(("/", fd, identity(os.fstat(fd))))
        for name in path.parts[1:]:
            fd = os.open(name, flags, dir_fd=fd)
            chain.append((name, fd, identity(os.fstat(fd))))
        verify()
        yield type("BoundDirectory", (), {"fd": fd, "verify": staticmethod(verify)})()
        verify()
    finally:
        for _name, fd, _meta in reversed(chain):
            os.close(fd)


def publish_export(dest, data):
    """A failed/partial write has no visible artifact filename."""
    parent = os.open(dest.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    temporary = "." + dest.name + ".partial-" + uuid.uuid4().hex
    created = linked = False
    try:
        meta = os.fstat(parent)
        require(stat.S_ISDIR(meta.st_mode) and meta.st_uid == 0 and not meta.st_mode & 0o022,
                "export_destination_untrusted")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                     0o600, dir_fd=parent)
        created = True
        try:
            offset = 0
            while offset < len(data):
                written = os.write(fd, data[offset:offset + 1024 * 1024])
                require(written > 0, "export_write_failed")
                offset += written
            os.fchmod(fd, 0o644)
            os.fsync(fd)
        finally:
            os.close(fd)
        os.link(temporary, dest.name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
        linked = True
        os.unlink(temporary, dir_fd=parent)
        created = False
        os.fsync(parent)
    except BaseException:
        if linked:
            os.unlink(dest.name, dir_fd=parent)
        raise
    finally:
        try:
            if created:
                os.unlink(temporary, dir_fd=parent)
        finally:
            os.close(parent)

# Runs inside the one Python sidecar before exec of the frozen live script.
# Only public, fixed metadata is emitted; no environment/config/log dump.
PREFLIGHT = r'''
import sys, os, json, stat, socket
def need(ok, reason):
    if not ok:
        print("Jovi, preflight BLOCKED: " + reason, flush=True)
        sys.exit(70)
need(sys.version_info[:3] == (3, 6, 8), "python_patch")
for key in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "GITHUB_TOKEN", "ACTIONS_RUNTIME_TOKEN"):
    need(key not in os.environ, "forwarded_environment")
for path in ("/", "/root", "/root/fixture", "/root/fixture/runs"):
    s = os.lstat(path)
    need(stat.S_ISDIR(s.st_mode) and s.st_uid == 0 and not s.st_mode & 0o022, "root_chain")
for path in ("/root", "/root/fixture", "/root/fixture/runs"):
    need(stat.S_IMODE(os.stat(path).st_mode) == 0o700, "directory_0700")
st = os.stat("/root/fixture/runs")
need("%s:%s" % (st.st_dev, st.st_ino) == sys.argv[1], "same_inode")
need(str(os.stat("/proc/self/ns/net").st_ino) == sys.argv[2], "same_netns")
interfaces = sorted(os.listdir("/sys/class/net"))
need(set(interfaces) <= {"lo", "docker0"}, "external_interface")
with open("/proc/net/route") as f:
    routes = [line.split() for line in f.read().splitlines()[1:]]
need(not any(len(r)>3 and r[1]=="00000000" and int(r[3],16)&1 for r in routes), "default_route")
with open("/proc/net/ipv6_route") as f:
    need(not any(r.split()[0]=="0"*32 and r.split()[1]=="00" and int(r.split()[8],16)&1 for r in f if r.split()), "ipv6_default_route")
for path in ("/proc/net/tcp", "/proc/net/tcp6"):
    with open(path) as f:
        need(not any(len(r.split())>3 and r.split()[3]=="0A" for r in f.read().splitlines()[1:]), "tcp_listener")
probe = "/root/fixture/runs/preflight-fsync"
fd = os.open(probe, os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW, 0o600)
os.write(fd, b"public-fsync-probe\n"); os.fsync(fd); os.close(fd)
fd = os.open("/root/fixture/runs", os.O_DIRECTORY|os.O_RDONLY); os.fsync(fd)
os.unlink(probe); os.fsync(fd); os.close(fd)
s = socket.socket(socket.AF_UNIX); s.settimeout(5); s.connect("/var/run/docker.sock"); s.close()
print("Jovi, " + json.dumps({"preflight": "PASS", "python": list(sys.version_info[:3]),
    "libc": os.confstr("CS_GNU_LIBC_VERSION"), "kernel": os.uname().release,
    "interfaces": interfaces, "shared_netns": True, "same_runs_inode": True,
    "directory_fsync": True, "readonly_socket_connect": True}, sort_keys=True), flush=True)
os.execv(sys.executable, [sys.executable, "-B", "/root/fixture/engine26_candidate.py",
    "--execute-local", "--isolated-daemon-attested", "--cached-alpine-id", sys.argv[3],
    "--min-available-mib", "384", "--port-conflict-order", "competitor-first"])
'''

CGROUP_PROBE = r'''set -eu
if test -r /sys/fs/cgroup/memory.max; then
  printf 'v2 memory='; cat /sys/fs/cgroup/memory.max
  printf 'swap='; cat /sys/fs/cgroup/memory.swap.max
  printf 'pids='; cat /sys/fs/cgroup/pids.max
else
  printf 'v1 memory='; cat /sys/fs/cgroup/memory/memory.limit_in_bytes
  printf 'swap='; cat /sys/fs/cgroup/memory/memory.memsw.limit_in_bytes
  printf 'pids='; cat /sys/fs/cgroup/pids/pids.max
fi
exit 37
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value, mode=0o600):
    """Durable replace only for this job's explicitly created bookkeeping files."""
    data = (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()
    tmp = path.with_name(path.name + ".new")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        os.fchmod(fd, mode)  # Public copies must remain readable under umask 077.
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
        fd = os.open(path.parent, os.O_DIRECTORY | os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        if tmp.exists():
            tmp.unlink()


def receipt_id(stdout, cid_text):
    value = stdout.strip()
    require(bool(FULL_ID.fullmatch(value)) and value == cid_text.strip(), "unreliable_create_receipt")
    return value


def validate_cgroup(output, code, inspected_exit):
    require(code == 37 and inspected_exit == 37, "shell_exit_not_preserved")
    allowed = ("v2 memory=16777216\nswap=0\npids=16\n",
               "v1 memory=16777216\nswap=16777216\npids=16\n")
    require(output in allowed, "cgroup_limits_not_enforced")
    return {"version": output[:2], "memory_bytes": 16777216,
            "memory_plus_swap_bytes": 16777216, "pids": 16,
            "shell_expected_exit": 37, "observed_exit": code, "inspect_exit": inspected_exit}


def dind_args(root, cidfile):
    return ["create", "--cidfile", str(cidfile), "--pull=never", "--platform=linux/amd64",
            "--privileged", "--network=none", "--tmpfs", TMPFS,
            "--mount", "type=bind,src=" + str(root / "data") + ",dst=/var/lib/docker",
            "--mount", "type=bind,src=" + str(root / "socket") + ",dst=/ci-socket",
            "--mount", "type=bind,src=" + str(root / "runs") + ",dst=/root/fixture/runs",
            REFS["dind"], "dockerd", "--host=unix:///ci-socket/docker.sock",
            "--data-root=/var/lib/docker", "--storage-driver=overlay2"]


def python_args(root, dind, cidfile, runs_inode, netns, alpine):
    args = ["create", "--cidfile", str(cidfile), "--pull=never", "--platform=linux/amd64",
            "--user=0:0", "--network=container:" + dind, "--cap-drop=ALL",
            "--security-opt=no-new-privileges:true", "--read-only", "--tmpfs", TMPFS]
    for name in HASHES:
        args += ["--mount", "type=bind,src=" + str(root / "src" / name) +
                 ",dst=/root/fixture/" + name + ",readonly"]
    args += ["--mount", "type=bind,src=" + str(root / "runs") + ",dst=/root/fixture/runs",
             "--mount", "type=bind,src=" + str(root / "socket") + ",dst=/var/run,readonly",
             "--workdir=/root/fixture", "--entrypoint=/usr/local/bin/python3", REFS["python"],
             "-B", "-c", PREFLIGHT, runs_inode, netns, alpine]
    return args


class Runner:
    def __init__(self, args, execute=subprocess.run):
        self.args, self.execute = args, execute
        self.root = Path("/root/ml26-ci-" + args.run + "-" + args.attempt)
        self.evidence = args.evidence
        self.state_path = self.root / "state.json"
        self.state = {}
        self.commands = []
        self.deadline = time.monotonic() + 840
        self.recovery_only = False

    def remaining(self):
        value = self.deadline - time.monotonic()
        if value <= 0:
            raise FinalizerTimeout("global_deadline_exceeded")
        return value

    def command(self, args, timeout=60, accepted=(0,), op="command"):
        # Always point the CLI at the known outer VM Unix socket, never contexts.
        command = ["docker", "--host=unix:///var/run/docker.sock"] + list(args)
        self.remaining()  # An expired budget never dispatches another command.
        digest = sha(json.dumps(command).encode())
        self.track({"op": op, "argv_sha256": digest, "status": "attempted_once"})
        try:
            result = self.execute(command, env=HOST_ENV, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, timeout=min(timeout, self.remaining()), check=False)
        except (subprocess.TimeoutExpired, OSError) as error:
            if op == "single_live_round" and isinstance(error, subprocess.TimeoutExpired) and error.stdout:
                (self.evidence / "harness-stdout-partial.txt").write_bytes(error.stdout)
                os.chmod(self.evidence / "harness-stdout-partial.txt", 0o644)
            self.track({"op": op, "argv_sha256": digest, "status": "timeout_or_launch_failure"})
            raise Rejected("command_timeout_or_launch_failure") from None
        self.track({"op": op, "argv_sha256": digest, "exit": result.returncode})
        require(result.returncode in accepted, "command_failed_" + op)
        return result

    def track(self, record):
        self.commands.append(record)
        if self.state_path.exists():
            write_json(self.root / "commands.json", self.commands)

    def inner(self, args, **kwargs):
        require(FULL_ID.fullmatch(self.state.get("dind", "")), "dind_receipt_required")
        return self.command(["exec", self.state["dind"], "docker", "-H",
                             "unix:///ci-socket/docker.sock"] + args, **kwargs)

    def save_state(self):
        write_json(self.state_path, self.state)

    def public(self, name, data):
        write_json(self.evidence / name, data, 0o644)

    def create(self, kind, args, cidfile, inner=False):
        # Mark intent before the single create. No retry/name lookup on ambiguity.
        self.state["pending_create"] = kind
        self.save_state()
        out = (self.inner if inner else self.command)(args, op="create_" + kind)
        value = receipt_id(out.stdout.decode("ascii"), cidfile.read_text(encoding="ascii"))
        self.state[kind] = value
        self.state.pop("pending_create")
        self.save_state()
        return value

    def initialize(self):
        require(sys.platform.startswith("linux") and os.geteuid() == 0 and os.uname().machine == "x86_64",
                "linux_amd64_root_required")
        require(re.fullmatch(r"[0-9]+", self.args.run) and self.args.attempt == "1" and
                re.fullmatch(r"[0-9a-f]{40}", self.args.sha), "job_identity_invalid")
        workspace = self.args.source.parent.parent.resolve()
        require(self.evidence == workspace / "engine26-evidence", "evidence_path_invalid")
        require(not self.root.exists() and not self.evidence.exists(), "existing_state_refuses_rerun")
        os.mkdir(self.root, 0o700)
        self.state = {"schema": 1, "run": self.args.run, "attempt": self.args.attempt,
                      "source_sha": self.args.sha, "nonce": uuid.uuid4().hex,
                      "cleanup": {}, "status": "BLOCKED", "round_started": False}
        self.save_state()
        os.mkdir(self.evidence, 0o755)
        os.chmod(self.evidence, 0o755)
        for name in ("src", "runs", "socket", "data"):
            os.mkdir(self.root / name, 0o700)
        data_stat = (self.root / "data").stat()
        self.state["data_root"] = {"path": str(self.root / "data"), "dev": data_stat.st_dev,
                                   "ino": data_stat.st_ino}
        self.save_state()
        for name, expected in HASHES.items():
            path = self.args.source / name
            require(path.is_file() and not path.is_symlink(), "frozen_source_type")
            data = path.read_bytes()
            require(sha(data) == expected, "frozen_source_hash")
            dest = self.root / "src" / name
            with open(dest, "xb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(dest, 0o444)
        result = self.execute(["git", "-c", "safe.directory=" + str(workspace), "-C", str(workspace),
                               "rev-parse", "HEAD", "HEAD^{tree}"], env=HOST_ENV, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=10, check=False)
        values = result.stdout.decode("ascii").splitlines()
        require(result.returncode == 0 and len(values) == 2 and values[0] == self.args.sha and
                all(re.fullmatch(r"[0-9a-f]{40}", x) for x in values), "source_identity")
        self.public("metadata.json", {"source_sha": values[0], "source_tree": values[1],
                    "runner_image": self.args.runner_image, "host_kernel": os.uname().release,
                    "host_distribution": "GitHub ubuntu-24.04", "images": REFS,
                    "input_sha256": HASHES, "order": "competitor-first", "runtime_not_production": True,
                    "preflight_code_sha256": sha(PREFLIGHT.encode()), "cgroup_code_sha256": sha(CGROUP_PROBE.encode())})

    def stage(self):
        images = {}
        for index, (kind, ref) in enumerate(REFS.items(), 1):
            print("Jovi, staging [{}/3] {}".format(index, kind), flush=True)
            self.command(["pull", "--platform=linux/amd64", ref], timeout=240, op="pull_" + kind)
            fmt = '{{json .Os}}|{{json .Architecture}}|{{json .Id}}|{{json .RepoDigests}}'
            value = self.command(["image", "inspect", ref, "--format", fmt], op="image_metadata").stdout.decode().strip()
            osname, arch, image_id, digests = map(json.loads, value.split("|"))
            require(osname == "linux" and arch == "amd64" and IMAGE_ID.fullmatch(image_id)
                    and any(x.endswith("@" + ref.split("@", 1)[1]) for x in digests), "pulled_image_identity")
            images[kind] = {"id": image_id, "repo_digests": digests, "os": osname, "arch": arch}
        self.command(["tag", REFS["alpine"], "alpine:3.20.3"], op="explicit_alpine_tag")
        self.public("image-metadata.json", images)
        # Save before network-isolated execution. No registry access from dind.
        self.command(["image", "save", "--output", str(self.root / "alpine.tar"), "alpine:3.20.3"],
                     timeout=90, op="save_cached_alpine")
        require(not any((self.root / "data").iterdir()), "data_root_must_start_empty")
        dind = self.create("dind", dind_args(self.root, self.root / "dind.cid"), self.root / "dind.cid")
        self.command(["start", dind], op="start_dind")
        ready = False
        for _ in range(30):
            value = self.inner(["version", "--format", '{{json .Server}}'], accepted=(0, 1), timeout=5, op="bounded_readiness")
            if value.returncode == 0:
                server = json.loads(value.stdout)
                require(server["Version"] == "26.1.3" and server["ApiVersion"] == "1.45" and
                        server["Os"] == "linux" and server["Arch"] == "amd64", "inner_server_version")
                ready = True
                break
            time.sleep(1)
        require(ready, "dind_readiness_failed")
        infofmt = '{{json .Driver}}|{{json .CgroupDriver}}|{{json .CgroupVersion}}|{{json .KernelVersion}}'
        info = self.inner(["info", "--format", infofmt], op="daemon_metadata").stdout.decode().strip()
        driver, cdriver, cversion, kernel = map(json.loads, info.split("|"))
        require(driver == "overlay2" and cversion in ("1", "2"), "storage_or_cgroup")
        self.public("daemon-metadata.json", {"server": {k: server[k] for k in ("Version", "ApiVersion", "Os", "Arch")},
                    "storage_driver": driver, "cgroup_driver": cdriver, "cgroup_version": cversion, "kernel": kernel})
        # docker cp is targeted parent-owned staging, not a repo/socket mount.
        self.command(["cp", str(self.root / "alpine.tar"), dind + ":/ci-socket/alpine.tar"], timeout=90, op="offline_copy_alpine")
        self.inner(["image", "load", "--input", "/ci-socket/alpine.tar"], timeout=90, op="offline_load_alpine")
        self.command(["exec", dind, "rm", "--", "/ci-socket/alpine.tar"], op="remove_staging_tar")
        alpine = self.inner(["image", "inspect", "alpine:3.20.3", "--format", '{{.Id}}'], op="inner_cached_id").stdout.decode().strip()
        require(alpine == images["alpine"]["id"] and IMAGE_ID.fullmatch(alpine), "cached_alpine_mismatch")
        config = self.inner(["image", "inspect", alpine, "--format", '{{json .Config}}'], op="alpine_config").stdout
        c = json.loads(config)
        require(c.get("Cmd") == ["/bin/sh"] and c.get("User", "") == "" and not c.get("Entrypoint") and
                not c.get("Volumes") and not c.get("ExposedPorts") and not c.get("Healthcheck"), "alpine_config_not_standard")
        self.public("cached-alpine.json", {"id": alpine, "config_sha256": sha(config), "standard_config": True})
        self.probe_cgroup(alpine)
        inode = self.command(["exec", dind, "stat", "-c", "%d:%i", "/root/fixture/runs"], op="runs_witness").stdout.decode().strip()
        netns = self.command(["exec", dind, "readlink", "/proc/1/ns/net"], op="netns_witness").stdout.decode().strip()
        require(re.fullmatch(r"[0-9]+:[0-9]+", inode) and re.fullmatch(r"net:\[[0-9]+\]", netns), "witness_format")
        py = self.create("python", python_args(self.root, dind, self.root / "python.cid", inode, netns[5:-1], alpine),
                         self.root / "python.cid")
        self.state["round_started"] = True
        self.save_state()
        result = self.command(["start", "--attach", py], timeout=600, accepted=tuple(range(0, 256)), op="single_live_round")
        self.state["python_exit"] = result.returncode
        self.save_state()
        # Frozen harness/preflight output is sanitized by construction. Never export stderr or daemon logs.
        (self.evidence / "harness-stdout.txt").write_bytes(result.stdout)
        os.chmod(self.evidence / "harness-stdout.txt", 0o644)
        inspect = self.command(["inspect", py, "--format", '{{json .State.Status}}|{{json .State.ExitCode}}'],
                               op="python_exit_witness").stdout.decode().strip()
        status, code = map(json.loads, inspect.split("|"))
        self.public("exit-status.json", {"attached_exit": result.returncode, "inspect_exit": code, "status": status})
        require(status == "exited" and code == result.returncode == 0, "harness_exit_failure")

    def probe_cgroup(self, alpine):
        cidfile = self.root / "socket" / "probe.cid"
        probe = self.create("probe", ["create", "--cidfile", "/ci-socket/probe.cid", "--pull=never",
                             "--network=none", "--memory=16m", "--memory-swap=16m", "--pids-limit=16",
                             "--cap-drop=ALL", "--security-opt=no-new-privileges:true", "--read-only",
                             alpine, "sh", "-c", CGROUP_PROBE], cidfile, inner=True)
        out = self.inner(["start", "--attach", probe], timeout=30, accepted=tuple(range(0, 256)), op="cgroup_exit_probe")
        code = int(self.inner(["inspect", probe, "--format", '{{.State.ExitCode}}'], op="probe_exit_witness").stdout)
        self.public("cgroup-probe.json", validate_cgroup(out.stdout.decode(), out.returncode, code))
        self.remove_container("probe", inner=True)
        require(self.state["cleanup"].get("probe") == "removed_exact_receipt", "probe_cleanup_failed")

    def remove_container(self, kind, inner=False):
        value = self.state.get(kind)
        if not value or self.state["cleanup"].get(kind):
            return
        require(FULL_ID.fullmatch(value), "invalid_cleanup_receipt")
        call = self.inner if inner else self.command
        try:
            output = call(["rm", "--force", value], op="remove_" + kind).stdout.decode().strip()
            require(output == value, "remove_receipt_mismatch")
            absent = call(["inspect", value], accepted=(0, 1), op="verify_absent_" + kind)
            require(absent.returncode == 1 and b"no such" in absent.stderr.lower(), "exact_absence_unproved")
            self.state["cleanup"][kind] = "removed_exact_receipt"
        except Rejected:
            self.state["cleanup"][kind] = "FAILED_OR_UNKNOWN_no_retry"
        self.save_state()

    def snapshot(self):
        if self.state.get("snapshot_attempted"):
            return
        self.state["snapshot_attempted"] = True
        self.save_state()
        # Only this job's brand-new bind root; never inspect other data or Docker listings.
        children = list((self.root / "runs").iterdir())
        require(len(children) == 1 and re.fullmatch(r"ml26[0-9a-f]{32}", children[0].name), "one_run_required")
        source = children[0]
        require(source.is_dir() and not source.is_symlink(), "run_directory_type")
        dest = self.evidence / source.name
        os.mkdir(dest, 0o755)
        os.chmod(dest, 0o755)
        allowed = {"result.json", "journal.jsonl", "health-observations.json"}
        failures = []
        for name in sorted(allowed):
            try:
                self.copy_safe(source / name, dest / name)
            except (Rejected, OSError):
                failures.append(name)
        receipts = source / "core-receipts"
        if receipts.is_dir() and not receipts.is_symlink():
            os.mkdir(dest / "core-receipts", 0o755)
            os.chmod(dest / "core-receipts", 0o755)
            for path in receipts.iterdir():
                try:
                    require(re.fullmatch(r"[0-9a-f]{32}\.(planned|created)\.json", path.name), "receipt_filename")
                    self.copy_safe(path, dest / "core-receipts" / path.name)
                except (Rejected, OSError):
                    failures.append("core-receipt-rejected")
            require(any((dest / "core-receipts").iterdir()), "core_receipts_empty")
        else:
            failures.append("core-receipts")
        self.public("snapshot-copy.json", {"failed_allowlist_items": failures,
                    "source_modes_unchanged": True, "other_files_exported": False})
        require(not failures, "allowlist_evidence_incomplete")
        self.state["acceptance"] = load_verify(dest)
        self.state["snapshot_sealed"] = True
        self.save_state()

    @staticmethod
    def copy_safe(source, dest):
        with trusted_directory(source.parent) as directory:
            fd = os.open(source.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory.fd)
            try:
                meta = os.fstat(fd)
                validate_export_metadata(meta)
                expected = identity(meta)
                require(identity(os.stat(source.name, dir_fd=directory.fd, follow_symlinks=False)) == expected,
                        "export_source_replaced")
                data = bytearray()
                while True:
                    chunk = os.read(fd, min(1024 * 1024, MAX_EVIDENCE_BYTES - len(data) + 1))
                    if not chunk:
                        break
                    require(len(data) + len(chunk) <= MAX_EVIDENCE_BYTES, "export_read_limit_exceeded")
                    data.extend(chunk)
                after = os.fstat(fd)
                require(identity(after) == expected and after.st_size == meta.st_size == len(data) and
                        identity(os.stat(source.name, dir_fd=directory.fd, follow_symlinks=False)) == expected,
                        "export_source_changed_during_read")
                directory.verify()
            finally:
                os.close(fd)
        publish_export(dest, bytes(data))

    def finalize(self):
        if not self.state or not self.evidence.exists():
            return False
        if self.state.get("finalized") and not self.recovery_only:
            return self.state["status"] == "EXPECTED_START_FAILURE_RECONCILED"
        if self.recovery_only:
            self.state["failure"] = "initial_step_failed_or_unknown_requires_review"
        self.deadline = time.monotonic() + 120
        armed = sys.platform.startswith("linux") and hasattr(signal, "setitimer")
        old_handler = None
        def expired(_signum, _frame):
            raise FinalizerTimeout("finalizer_global_deadline_exceeded")
        if armed:
            old_handler = signal.signal(signal.SIGALRM, expired)
            signal.setitimer(signal.ITIMER_REAL, self.remaining())
        self.state["status"] = "BLOCKED"
        try:
            self.public("finalizer-status.json", {"status": "BLOCKED", "reason": "finalizer_in_progress",
                                                 "budget_seconds": 120})
            result = self._finalize_body()
            self.remaining()
            self.public("finalizer-status.json", {"status": "EXPECTED_START_FAILURE_RECONCILED" if result else "BLOCKED",
                        "complete": True, "budget_seconds": 120, "recovery_only": self.recovery_only})
            self.remaining()
            self.seal_manifest()
            self.remaining()
            return result
        except FinalizerTimeout:
            # Do not begin more I/O or cleanup after the global deadline. The job fails.
            # The always() step receives the first step outcome and cannot promote a failure.
            self.state["status"] = "BLOCKED"
            self.state["finalizer_timed_out"] = True
            self.state["failure"] = "finalizer_global_deadline_exceeded"
            print("Jovi, BLOCKED: finalizer_global_deadline_exceeded", flush=True)
            return False
        finally:
            if armed:
                signal.setitimer(signal.ITIMER_REAL, 0)
                signal.signal(signal.SIGALRM, old_handler)

    def _finalize_body(self):
        # A forced interruption cannot be a passing run. Stop the known writer before sealing partial evidence.
        if self.state.get("python") and "python_exit" not in self.state:
            try:
                self.command(["stop", "--time=10", self.state["python"]], timeout=20, op="stop_interrupted_writer")
            except Rejected:
                self.state["writer_stop_failed"] = True
        try:
            if self.state.get("round_started") and not self.state.get("writer_stop_failed"):
                self.snapshot()
        except (Rejected, OSError, ValueError, KeyError, TypeError):
            self.state["evidence_error"] = "evidence_incomplete_or_rejected"
        # Export before removing daemon/sidecar. Unknown creation intent stays BLOCKED.
        self.public("pre-cleanup.json", {"snapshot_sealed": self.state.get("snapshot_sealed", False),
                    "pending_create": self.state.get("pending_create"), "python_exit": self.state.get("python_exit"),
                    "evidence_error": self.state.get("evidence_error")})
        if self.state.get("probe") and not self.state["cleanup"].get("probe"):
            self.remove_container("probe", inner=True)
        self.remove_container("python")
        self.remove_container("dind")
        data_root = self.state.get("data_root")
        if data_root and not self.state["cleanup"].get("data_root"):
            try:
                require(not self.state.get("pending_create") and
                        self.state["cleanup"].get("dind") == "removed_exact_receipt", "data_root_daemon_not_absent")
                target = Path(data_root["path"])
                meta = target.lstat()
                require(target == self.root / "data" and target.resolve() == self.root.resolve() / "data" and
                        stat.S_ISDIR(meta.st_mode) and not target.is_symlink() and meta.st_uid == 0 and
                        stat.S_IMODE(meta.st_mode) == 0o700 and meta.st_dev == data_root["dev"] and
                        meta.st_ino == data_root["ino"], "data_root_receipt_changed")
                require(getattr(shutil.rmtree, "avoids_symlink_attacks", False), "safe_rmtree_required")
                shutil.rmtree(target)  # Linux fd-based symlink-safe implementation only.
                require(not target.exists(), "data_root_absence_unproved")
                self.state["cleanup"]["data_root"] = "removed_exact_receipt"
            except (Rejected, OSError, ValueError):
                self.state["cleanup"]["data_root"] = "FAILED_OR_UNKNOWN_no_retry"
        clean = not self.state.get("pending_create") and all(x == "removed_exact_receipt" for x in self.state["cleanup"].values())
        success = (self.state.get("python_exit") == 0 and self.state.get("snapshot_sealed") is True and
                   not self.state.get("pending_create") and not self.state.get("failure") and clean and
                   not self.recovery_only and
                   all(k in self.state["cleanup"] for k in ("dind", "python", "probe", "data_root")))
        self.state["status"] = "EXPECTED_START_FAILURE_RECONCILED" if success else "BLOCKED"
        self.save_state()
        self.public("cleanup.json", {"receipts": {k: self.state[k] for k in ("dind", "python", "probe", "data_root") if k in self.state},
                    "results": self.state["cleanup"], "pending_create": self.state.get("pending_create"),
                    "all_known_cleanup_proven": clean, "vm_destruction_is_not_cleanup_proof": True})
        self.public("status.json", {"status": self.state["status"], "failure": self.state.get("failure"),
                    "actual_exit": self.state.get("python_exit"), "snapshot_sealed": self.state.get("snapshot_sealed", False),
                    "acceptance": self.state.get("acceptance"), "second_round_started": False})
        self.public("commands.json", self.commands)
        self.seal_manifest()
        self.state["finalized"] = True
        self.save_state()
        return bool(success)

    def seal_manifest(self):
        rows = []
        for path in sorted(self.evidence.rglob("*")):
            if path.is_file() and path.name != "SHA256SUMS" and not any(p.startswith(".") for p in path.relative_to(self.evidence).parts):
                require(not path.is_symlink(), "evidence_symlink")
                rows.append(sha(path.read_bytes()) + "  " + path.relative_to(self.evidence).as_posix())
        output = self.evidence / "SHA256SUMS"
        output.write_text("\n".join(rows) + "\n", encoding="ascii")
        os.chmod(output, 0o644)


def main(argv=None):
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--run", dest="mode", action="store_const", const="run")
    modes.add_argument("--cleanup-only", dest="mode", action="store_const", const="cleanup")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--run-id", dest="run", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--runner-image", required=True)
    parser.add_argument("--first-outcome", default="unknown", choices=("success", "failure", "cancelled", "skipped", "unknown"))
    args = parser.parse_args(argv)
    require(re.fullmatch(r"[0-9]+", args.run) and args.attempt == "1" and
            re.fullmatch(r"[0-9a-f]{40}", args.sha), "job_identity_invalid")
    require(args.source.is_absolute() and args.evidence.is_absolute() and
            args.evidence == args.source.parent.parent.resolve() / "engine26-evidence", "evidence_path_invalid")
    runner = Runner(args)
    if args.mode == "cleanup":
        runner.recovery_only = args.first_outcome != "success"
        require(sys.platform.startswith("linux") and os.geteuid() == 0, "linux_root_required")
        if not runner.state_path.exists():
            return 1  # Never invent receipts or create a new run from finalizer.
        root_meta = runner.root.lstat()
        state_meta = runner.state_path.lstat()
        require(stat.S_ISDIR(root_meta.st_mode) and root_meta.st_uid == 0 and
                stat.S_IMODE(root_meta.st_mode) == 0o700 and not runner.root.is_symlink() and
                stat.S_ISREG(state_meta.st_mode) and state_meta.st_uid == 0 and
                stat.S_IMODE(state_meta.st_mode) == 0o600 and state_meta.st_nlink == 1,
                "cleanup_state_not_trusted")
        runner.state = json.loads(runner.state_path.read_text())
        if (runner.root / "commands.json").is_file():
            runner.commands = json.loads((runner.root / "commands.json").read_text())
        require(runner.state.get("run") == args.run and runner.state.get("attempt") == args.attempt and
                runner.state.get("source_sha") == args.sha, "cleanup_identity_mismatch")
        return 0 if runner.finalize() else 1
    def interrupted(signum, _frame):
        raise Rejected("job_signal_" + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    signal.alarm(840)  # 14-minute entire execution budget, then <=2-minute cleanup; 20-minute job.
    try:
        runner.initialize()
        runner.stage()
    except BaseException as error:
        # Constant reason only. Raw exceptions/daemon diagnostics are never published.
        reason = str(error) if isinstance(error, Rejected) else "orchestrator_failed_review_stage"
        if runner.state:
            runner.state["failure"] = reason
            runner.save_state()
        print("Jovi, BLOCKED: " + reason, flush=True)
    finally:
        signal.alarm(0)
        good = runner.finalize()
    return 0 if good else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        print("Jovi, BLOCKED: finalizer_or_argument_failure", flush=True)
        sys.exit(1)
