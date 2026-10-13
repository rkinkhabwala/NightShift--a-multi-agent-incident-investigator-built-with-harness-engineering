"""Helpers to write model-response cassettes for loop tests (sequence-mode ReplayAdapter)."""

import json
from pathlib import Path


def call(name, **arguments):
    return {"type": "function", "function": {"name": name, "arguments": arguments}}


def response(*tool_calls, content="", prompt_tokens=900, completion_tokens=60):
    return {"model": "qwen3:8b", "done": True, "done_reason": "stop",
            "message": {"role": "assistant", "content": content, "tool_calls": list(tool_calls)},
            "prompt_eval_count": prompt_tokens, "eval_count": completion_tokens, "total_duration": 1_000_000_000}


def finish(**report):
    return response(call("finish", **report))


def no_cause(summary="Telemetry looks normal; nothing explains the alert."):
    return {"status": "NO_ROOT_CAUSE", "summary": summary, "root_cause": None, "findings": [],
            "hypotheses_not_confirmed": [], "remediation": None}


def payments_deploy(evidence=("ev-0001",)):
    return {"status": "ROOT_CAUSE_FOUND",
            "summary": "payments 1.4 deploy introduced NullPointerExceptions for customers without a loyalty tier.",
            "root_cause": {"service": "payments", "category": "deploy", "confidence": "high",
                           "statement": "Deploy of payments 1.4 causes NullPointerException in the discount path."},
            "findings": [{"claim": "payments 5xx ratio rose to about 30 % after the 1.4 deploy",
                          "evidence_ids": list(evidence), "confidence": "high",
                          "falsifiable_check": "query_metrics error_rate for payments before the deploy"}],
            "hypotheses_not_confirmed": [],
            "remediation": {"action": "rollback_deploy", "target": "payments", "params": {"to_version": "1.3"},
                            "expected_effect": "payments 5xx back to baseline", "rollback": "redeploy 1.4"}}


def write_cassette(path: Path, entries) -> Path:
    """entries: list of response dicts or (expect, response) tuples."""
    with path.open("w") as f:
        for e in entries:
            expect, resp = e if isinstance(e, tuple) else ({}, e)
            f.write(json.dumps({"expect": expect, "response": resp}) + "\n")
    return path
