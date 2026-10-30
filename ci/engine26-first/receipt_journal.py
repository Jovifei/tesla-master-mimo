"""Sanitized append-only receipts. No recovery/replay/delete operation exists."""
import abc
import hashlib
import json
import os
import pathlib
import re
import stat
import threading


class DurabilityError(Exception):
    pass


def validate(record):
    keys = {"schema", "phase", "attempt_id", "target_name", "ownership_token"}
    if record.get("phase") == "created":
        keys.add("container_id")
    if (set(record) != keys or record.get("schema") != 1
            or record.get("phase") not in ("planned", "created")
            or re.fullmatch(r"[a-f0-9]{32}", record.get("attempt_id", "")) is None
            or re.fullmatch(r"[a-f0-9]{32}", record.get("ownership_token", "")) is None
            or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{1,199}", record.get("target_name", "")) is None
            or (record["phase"] == "created" and re.fullmatch(r"[a-f0-9]{64}", record.get("container_id", "")) is None)):
        raise DurabilityError("invalid_sanitized_record")
    return json.dumps(record, sort_keys=True, separators=(",", ":")).encode("ascii")


class DurableAck:
    """Python 3.6 immutable value equivalent of the frozen dataclass."""
    __slots__ = ("attempt_id", "phase", "container_id", "record_sha256")
    def __init__(self, attempt_id, phase, container_id, record_sha256):
        for key, value in zip(self.__slots__, (attempt_id, phase, container_id, record_sha256)):
            object.__setattr__(self, key, value)
    def __setattr__(self, name, value):
        raise AttributeError("immutable_durable_ack")
    def __delattr__(self, name):
        raise AttributeError("immutable_durable_ack")
    def __eq__(self, other):
        return type(self) is type(other) and all(getattr(self, key) == getattr(other, key) for key in self.__slots__)
    def __hash__(self):
        return hash(tuple(getattr(self, key) for key in self.__slots__))


def acknowledge(record):
    return DurableAck(record["attempt_id"], record["phase"], record.get("container_id"),
                      hashlib.sha256(validate(record)).hexdigest())


def verified_ack(record, ack):
    return isinstance(ack, DurableAck) and ack == acknowledge(record)


class ReceiptStore(abc.ABC):
    """Trusted implementation contract: return only after file AND directory sync.

    Exceptions, missing/mismatched acknowledgments, and unsupported storage must
    fail closed. An acknowledgment does not make an untrusted store trustworthy.
    """
    @abc.abstractmethod
    def write_plan(self, record):
        raise NotImplementedError
    @abc.abstractmethod
    def write_created(self, record):
        raise NotImplementedError


class PosixReceiptStore(ReceiptStore):
    """Existing private controlled directory, same-host POSIX filesystem only.

    No symlink traversal, overwrite, implicit directory creation, journal replay,
    automatic adoption, or automatic deletion. Partial writes are left for
    manual inspection. Windows directory durability is deliberately unsupported.
    """
    def __init__(self, root):
        self._fd = None
        self._closed = True
        self._plans = {}
        self._lock = threading.Lock()
        if os.name != "posix" or not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
            raise DurabilityError("posix_directory_durability_required")
        path = pathlib.PurePosixPath(root)
        if not path.is_absolute() or ".." in path.parts or str(path) == "/":
            raise DurabilityError("controlled_journal_directory_required")
        self._parts = path.parts[1:]
        self._uid = os.geteuid()
        opened, pinned = [], None
        try:
            opened, chain = self._open_trusted_chain()
            os.fsync(opened[-2])
            os.fsync(opened[-1])
            pinned = opened.pop()
            if self._dispose(opened):
                raise DurabilityError("journal_descriptor_cleanup_failed")
            self._fd, pinned = pinned, None
            self._chain = chain
            self._closed = False
        except OSError:
            raise DurabilityError("journal_directory_not_durable") from None
        finally:
            self._dispose(opened)
            if pinned is not None:
                self._dispose([pinned])

    @staticmethod
    def _dispose(descriptors):
        failed = False
        while descriptors:
            descriptor = descriptors.pop()
            try:
                os.close(descriptor)
            except OSError:
                failed = True  # Never retry a close whose FD may have been reused.
        return failed

    @staticmethod
    def _identity(metadata):
        return metadata.st_dev, metadata.st_ino, metadata.st_uid, metadata.st_mode

    def _open_trusted_chain(self):
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        opened, chain = [], []
        try:
            opened.append(os.open("/", flags))
            for index in range(len(self._parts) + 1):
                if index:
                    opened.append(os.open(self._parts[index - 1], flags, dir_fd=opened[-1]))
                metadata = os.fstat(opened[-1])
                if (not stat.S_ISDIR(metadata.st_mode)
                        or metadata.st_uid not in (0, self._uid)
                        or stat.S_IMODE(metadata.st_mode) & 0o022):
                    raise DurabilityError("untrusted_journal_ancestor")
                if index == len(self._parts) and (metadata.st_uid != self._uid
                        or stat.S_IMODE(metadata.st_mode) & 0o077):
                    raise DurabilityError("private_owned_journal_directory_required")
                chain.append(self._identity(metadata))
            return opened, tuple(chain)
        except BaseException:
            self._dispose(opened)
            raise

    def _ensure_open_locked(self):
        if self._closed or type(self._fd) is not int or self._fd < 0:
            raise DurabilityError("journal_closed")

    def _verify_root_locked(self):
        opened = []
        try:
            if os.geteuid() != self._uid:
                raise DurabilityError("journal_process_identity_changed")
            opened, chain = self._open_trusted_chain()
            if chain != self._chain or self._identity(os.fstat(self._fd)) != self._chain[-1]:
                raise DurabilityError("journal_directory_changed")
        except OSError:
            raise DurabilityError("journal_directory_changed") from None
        finally:
            if self._dispose(opened):
                raise DurabilityError("journal_descriptor_cleanup_failed")

    def _write(self, record):
        # Called only while write_plan/write_created holds the shared close lock.
        self._ensure_open_locked()
        data = validate(record) + b"\n"
        self._verify_root_locked()  # Every precondition fails before file creation.
        name = record["attempt_id"] + "." + record["phase"] + ".json"
        descriptor = None
        try:
            descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=self._fd)
            remaining = memoryview(data)
            while remaining:
                count = os.write(descriptor, remaining)
                if count <= 0:
                    raise OSError("no progress")
                remaining = remaining[count:]
            os.fsync(descriptor)
            closing_descriptor, descriptor = descriptor, None
            os.close(closing_descriptor)  # Never retry this number, even if close reports failure.
            os.fsync(self._fd)  # A file fsync alone is insufficient.
            return acknowledge(record)
        except OSError:
            raise DurabilityError("journal_record_not_durable") from None
        finally:
            if descriptor is not None:
                closing_descriptor, descriptor = descriptor, None
                os.close(closing_descriptor)

    def write_plan(self, record):
        with self._lock:
            self._ensure_open_locked()
            if record.get("phase") != "planned":
                raise DurabilityError("planned_record_required")
            ack = self._write(record)
            self._plans[record["attempt_id"]] = record.copy()
            return ack

    def write_created(self, record):
        with self._lock:
            self._ensure_open_locked()
            planned = self._plans.get(record.get("attempt_id"))
            expected = record.copy()
            expected.pop("container_id", None)
            expected["phase"] = "planned"
            if record.get("phase") != "created" or expected != planned:
                raise DurabilityError("current_process_plan_required")
            return self._write(record)

    def close(self):
        with self._lock:
            if self._closed:
                return
            descriptor = self._fd
            self._closed = True
            self._fd = None  # Invalidate before close can release/reuse its number.
            if descriptor is not None:
                os.close(descriptor)  # No retries after an ambiguous close error.
