"""Harness configuration: one TOML file, validated with Pydantic, logged with every eval run."""

from __future__ import annotations

import hashlib
import json
import os
import tomllib
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

DEFAULT_PATH = Path(__file__).resolve().parents[1] / "config" / "nightshift.toml"


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ModelConfig(_Strict):
    provider: str = "ollama"
    name: str
    base_url: str
    num_ctx: int = Field(ge=2048)
    keep_alive: str = "30m"
    seed: int | None = 42
    request_timeout_s: float = 600
    max_retries: int = Field(default=3, ge=0, le=10)


class RoleConfig(_Strict):
    temperature: float = Field(ge=0, le=2)
    think: bool = False
    max_steps: int = Field(ge=1, le=50)
    max_input_tokens: int = Field(ge=1000)
    wall_clock_s: float = Field(gt=0)
    max_tool_calls_per_step: int = Field(default=4, ge=1, le=16)


class ContextConfig(_Strict):
    compaction_ratio: float = Field(gt=0, lt=1)
    chars_per_token: float = Field(gt=0)


class ToolsConfig(_Strict):
    log_sample_lines: int = Field(ge=1, le=100)
    log_top_signatures: int = Field(ge=1, le=50)
    metric_max_series: int = Field(ge=1, le=100)
    trace_max_results: int = Field(ge=1, le=100)
    default_window_minutes: int = Field(ge=1, le=24 * 60)


class SnapshotConfig(_Strict):
    enabled: bool = True
    window_minutes: int = Field(ge=1)


class EndpointsConfig(_Strict):
    prometheus: str
    loki: str
    tempo: str
    ops: str
    chaos: str


class TracingConfig(_Strict):
    enabled: bool = True
    otlp_endpoint: str
    service_name: str = "nightshift-harness"


class StoreConfig(_Strict):
    path: str


class EvalConfig(_Strict):
    alert_wait_extra_s: float = 180
    baseline_timeout_s: float = 720


class Settings(_Strict):
    model: ModelConfig
    roles: dict[str, RoleConfig]
    context: ContextConfig
    tools: ToolsConfig
    snapshot: SnapshotConfig
    endpoints: EndpointsConfig
    tracing: TracingConfig
    store: StoreConfig
    eval: EvalConfig

    def role(self, name: str) -> RoleConfig:
        if name not in self.roles:
            raise KeyError(f"no [roles.{name}] section in config (have: {sorted(self.roles)})")
        return self.roles[name]

    def fingerprint(self) -> str:
        """Stable short hash of the full config, stored with every eval run."""
        blob = json.dumps(self.model_dump(mode="json"), sort_keys=True).encode()
        return hashlib.sha256(blob).hexdigest()[:12]


def load(path: str | Path | None = None, overrides: dict | None = None) -> Settings:
    """Loads the TOML config (NIGHTSHIFT_CONFIG or the default) and applies dotted overrides,
    e.g. {"model.name": "llama3.1:8b"}."""
    p = Path(path or os.environ.get("NIGHTSHIFT_CONFIG") or DEFAULT_PATH)
    data = tomllib.loads(p.read_text())
    for dotted, value in (overrides or {}).items():
        node = data
        *parents, leaf = dotted.split(".")
        for key in parents:
            node = node.setdefault(key, {})
        node[leaf] = value
    return Settings.model_validate(data)
