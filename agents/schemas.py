"""Structured agent output (spec §5.1 structured finish, §5.4 findings, §5.6 action policy).
These models are also handed to Ollama as JSON schema for the finish tool and the repair call."""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from harness import guards

Confidence = Literal["low", "medium", "high"]
Service = Literal["gateway", "orders", "payments", "inventory", "postgres", "redis", "none"]
Category = Literal["deploy", "config", "flag", "dependency", "resource", "code", "network", "none", "unknown"]
EVIDENCE_ID = re.compile(r"^ev-\d{4}$")


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Finding(_Strict):
    claim: str = Field(min_length=5, max_length=500, description="one factual statement about the system")
    evidence_ids: list[str] = Field(min_length=1, max_length=6, description="ids like ev-0003 that support the claim")
    confidence: Confidence
    falsifiable_check: str = Field(min_length=5, max_length=400, description="a query that would show the claim is wrong")

    @field_validator("evidence_ids")
    @classmethod
    def ids_look_right(cls, v: list[str]) -> list[str]:
        bad = [x for x in v if not EVIDENCE_ID.match(x)]
        if bad:
            raise ValueError(f"evidence ids must look like ev-0001, got {bad}")
        return v


class RootCause(_Strict):
    service: Service = Field(description="the component where the cause is, not where symptoms show")
    category: Category
    statement: str = Field(min_length=5, max_length=500)
    confidence: Confidence


class Remediation(_Strict):
    action: Literal["rollback_deploy", "restart_service", "toggle_flag", "revert_config", "scale_service", "escalate"]
    target: str = Field(description="service name, or the team/system to escalate to")
    params: dict[str, str] = Field(default_factory=dict,
                                   description='e.g. {"to_version": "1.3"} or {"flag": "new_pricing", "value": "false"} or {"key": "...", "value": "..."}')
    expected_effect: str = Field(min_length=3, max_length=300)
    rollback: str = Field(min_length=3, max_length=300, description="how to undo this action")

    @model_validator(mode="after")
    def policy(self):
        problems = guards.check_remediation(self.model_dump())
        if problems:
            raise ValueError("; ".join(problems))
        return self


class Report(_Strict):
    status: Literal["ROOT_CAUSE_FOUND", "NO_ROOT_CAUSE"]
    summary: str = Field(min_length=5, max_length=1200)
    root_cause: RootCause | None = None
    findings: list[Finding] = Field(default_factory=list, max_length=10)
    hypotheses_not_confirmed: list[str] = Field(default_factory=list, max_length=10)
    remediation: Remediation | None = None

    @model_validator(mode="after")
    def consistent(self):
        if self.status == "ROOT_CAUSE_FOUND":
            if self.root_cause is None:
                raise ValueError("status ROOT_CAUSE_FOUND needs root_cause")
            if not self.findings:
                raise ValueError("status ROOT_CAUSE_FOUND needs at least one finding with evidence_ids")
        if self.status == "NO_ROOT_CAUSE" and self.remediation is not None:
            raise ValueError("NO_ROOT_CAUSE must not propose a remediation")
        return self
