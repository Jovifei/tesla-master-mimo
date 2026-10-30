"""Offline acceptance of sanitized v7 evidence; no imports of live harness."""
import json
import re

ID = re.compile(r"[0-9a-f]{64}\Z")
CASES = ["rprivate-bind-named-user-port-two-networks", "wrong-uid-rejected",
         "bind-replaced-before-create", "bind-replaced-before-start", "symlink-rejected",
         "unsupported-bind-option-rejected", "name-competitor-first", "name-recovery-first",
         "concurrent-name-create", "same-session-concurrent-recover",
         "self-exit-unless-stopped-15s", "persistent-unhealthy-unless-stopped-15s",
         "port-competitor-first"]


class Rejected(Exception):
    pass


def require(value, reason):
    if not value:
        raise Rejected(reason)


def verify(result, events):
    require(result.get("error") is None and result.get("result_status") ==
            "EXPECTED_START_FAILURE_RECONCILED", "result_not_reconciled")
    require(result.get("port_conflict_order") == "competitor-first", "wrong_round")
    require([x.get("case") for x in result["cases"]] == CASES and
            all(x.get("status") == "pass" for x in result["cases"]), "case_order_incomplete")
    require([x.get("sequence") for x in events] == list(range(1, len(events) + 1)), "journal_sequence")
    require(result.get("production_access") is False and result.get("pulls") is False and
            result.get("application_acceptance") is False and result.get("unknown_history_preserved") is True,
            "scope_or_history_changed")
    cleanup = result["cleanup"]
    require(not cleanup.get("retained") and not cleanup.get("cleanup_failed"), "retained_or_cleanup_failed")
    require(cleanup.get("enumeration") is False and cleanup.get("cached_image_deleted") is False,
            "unsafe_cleanup")
    budget = cleanup["container_budget"]
    require(budget.get("allocation_closed") is True and budget.get("reserved") == 0, "not_sealed")
    marker = result["expected_start_failure_reconciliation"]
    require(isinstance(marker, dict) and marker.get("status") == "EXPECTED_START_FAILURE_RECONCILED"
            and marker.get("case") == CASES[-1] and marker.get("exact_id_404_persisted") is True
            and marker.get("original_unknown_preserved") is True
            and marker.get("allocation_still_closed") is True, "marker_invalid")
    target, plan = marker.get("container_id"), marker.get("plan")
    require(isinstance(target, str) and ID.fullmatch(target), "target_full_id")
    unknown = [x for x in events if x.get("event") == "unknown"]
    require(len(unknown) == 1 and unknown[0].get("plan") == plan and
            unknown[0].get("api_kind") == "start" and unknown[0].get("http_status") == 500,
            "extra_or_wrong_unknown")
    uncertain = cleanup.get("uncertain_posts", [])
    require(len(uncertain) == 1 and uncertain[0].get("plan") == plan and
            uncertain[0].get("api_kind") == "start" and uncertain[0].get("http_status") == 500 and
            uncertain[0].get("unknown_record_persisted") is True and cleanup.get("unknown_plans") == [plan],
            "extra_or_unpersisted_uncertainty")
    require(any(x.get("event") == "receipt" and x.get("kind") == "container" and
                x.get("id") == target and x.get("plan") == marker.get("receipt_plan") for x in events),
            "original_201_receipt_missing")
    # In v7 a receipt only follows a valid 201. It replaces a generic reply event.
    require(any(x.get("event") == "plan" and x.get("sequence") == marker.get("receipt_plan") and
                x.get("method") == "POST" and x.get("api_kind") == "container" for x in events),
            "original_create_plan_missing")
    require(any(x.get("event") == "plan" and x.get("sequence") == plan and
                x.get("method") == "POST" and x.get("api_kind") == "start" and
                x.get("intent", {}).get("id") == target for x in events), "start_target_mismatch")
    terminal = [x for x in events if x.get("event") == "case"]
    require([x.get("case") for x in terminal] == CASES and all(x.get("status") == "pass" for x in terminal),
            "journal_case_order")
    require(terminal[-1].get("terminal") is True and terminal[-1].get("case_index") == 13 and
            terminal[-1].get("total_cases") == 13 and terminal[-1].get("sequence") > plan and
            terminal[-1].get("expected_start_failure_id") == target, "terminal_proof_missing")
    deletes = {x["sequence"] for x in events if x.get("event") == "plan" and
               x.get("method") == "DELETE" and x["sequence"] > plan and x.get("intent", {}).get("id") == target}
    replies = [x["sequence"] for x in events if x.get("event") == "reply" and
               x.get("plan") in deletes and x.get("http_status") == 204]
    require(bool(replies) and any(x.get("event") == "container_absent" and x.get("id") == target and
                                 x.get("verified_404") is True and x["sequence"] > min(replies) for x in events),
            "owned_delete_and_404_missing")
    marks = [x for x in events if x.get("event") == "EXPECTED_START_FAILURE_RECONCILED"]
    require(len(marks) == 1 and all(marks[0].get(k) == v for k, v in marker.items()) and
            marks[0]["sequence"] > max(x["sequence"] for x in terminal), "persisted_marker_missing")
    known = sum(x.get("event") == "receipt" and x.get("kind") in ("container", "network", "image") for x in events)
    require(cleanup.get("known_resources_absent") == known, "known_absence_count")
    require(not any(x.get("event") == "plan" and x.get("method") == "POST" and
                    x["sequence"] > plan for x in events), "post_after_seal")
    return {"status": "EXPECTED_START_FAILURE_RECONCILED", "cases": 13,
            "unknown_history_count": 1, "allocation_still_closed": True}


def load_verify(directory):
    with open(directory / "result.json", encoding="utf-8") as stream:
        result = json.load(stream)
    with open(directory / "journal.jsonl", encoding="utf-8") as stream:
        events = [json.loads(line) for line in stream]
    return verify(result, events)
