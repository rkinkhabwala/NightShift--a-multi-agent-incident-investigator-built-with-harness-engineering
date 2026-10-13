"""The one agent loop every agent uses (spec §5.1).

model call -> tool calls (concurrent; all READ tools are independent and idempotent) -> results ->
repeat, until the agent calls finish(...) or a budget runs out. A budget never causes a silent hang:
the harness forces a final finish call and, failing that, writes a partial report itself."""

from __future__ import annotations

import asyncio
import json
import re
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Literal

from opentelemetry import trace
from pydantic import BaseModel, ValidationError

from harness.config import RoleConfig
from harness.context import ContextBuilder, Turn, compact_result
from harness.evidence import EvidenceBoard
from harness.models.base import ChatRequest, ModelAdapter, ModelError, ModelResponse, ToolCall, assistant_message, tool_message
from harness.tools.base import ToolContext, ToolSet, simplify_schema

tracer = trace.get_tracer("nightshift.harness")

Status = Literal["FINISHED", "BUDGET_EXCEEDED", "FINISH_INVALID", "ERROR"]

NUDGE = ("You replied without calling a tool. Continue the investigation by calling one of your tools, "
         "or call finish(...) with your report if you are done.")
FORCE_FINISH = ("Your {reason} budget is used up. Call finish(...) now with the report you can support from the "
                "evidence gathered so far. Use status NO_ROOT_CAUSE if the evidence does not establish a cause.")
REPAIR = ("Your finish(...) arguments were invalid:\n{errors}\n"
          "Reply with only the corrected report as a JSON object that matches the schema. Do not add any text.")
FORCE_FINISH_TIMEOUT_S = 180


@dataclass
class RunStats:
    steps: int = 0
    model_calls: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    tool_calls: int = 0
    tool_errors: int = 0
    write_attempts: int = 0
    nudges: int = 0
    repairs: int = 0
    compactions: int = 0
    model_ms: float = 0.0
    elapsed_s: float = 0.0
    max_prompt_tokens: int = 0


@dataclass
class RunResult:
    status: Status
    report: dict[str, Any] | None
    stats: RunStats
    transcript: list[dict[str, Any]] = field(default_factory=list)
    error: str | None = None
    budget_reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"status": self.status, "report": self.report, "stats": asdict(self.stats),
                "error": self.error, "budget_reason": self.budget_reason, "transcript": self.transcript}


def finish_tool_schema(report_model: type[BaseModel]) -> dict[str, Any]:
    return {"type": "function", "function": {
        "name": "finish",
        "description": ("Submit your final incident report. Call exactly once, when you are done. Every finding must "
                        "cite evidence ids (ev-NNNN) returned by your tools."),
        "parameters": simplify_schema(report_model.model_json_schema())}}


def _json_in(text: str) -> dict[str, Any] | None:
    """Small models sometimes write the report as text instead of calling finish; accept it if it parses."""
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        value = json.loads(m.group(0))
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


class AgentLoop:
    def __init__(self, role: str, model: ModelAdapter, tools: ToolSet, ctx: ToolContext, board: EvidenceBoard,
                 context: ContextBuilder, cfg: RoleConfig, report_model: type[BaseModel],
                 clock=time.monotonic):
        self.role = role
        self.model = model
        self.tools = tools
        self.ctx = ctx
        self.board = board
        self.context = context
        self.cfg = cfg
        self.report_model = report_model
        self.clock = clock
        self.stats = RunStats()
        self.transcript: list[dict[str, Any]] = []
        self._finish_schema = finish_tool_schema(report_model)

    # --- public ------------------------------------------------------------------------------------
    async def run(self, task: str) -> RunResult:
        with tracer.start_as_current_span(f"agent {self.role}", attributes={
                "nightshift.agent": self.role, "nightshift.model": self.model.model_id,
                "nightshift.budget.max_steps": self.cfg.max_steps}) as span:
            started = self.clock()
            try:
                result = await self._run(task, started)
            except ModelError as e:
                result = self._result("ERROR", None, error=f"model error: {e}")
            self.stats.elapsed_s = round(self.clock() - started, 2)
            self.stats.compactions = self.context.compactions
            span.set_attribute("nightshift.status", result.status)
            for k, v in asdict(self.stats).items():
                span.set_attribute(f"nightshift.stats.{k}", v)
            return result

    # --- loop --------------------------------------------------------------------------------------
    async def _run(self, task: str, started: float) -> RunResult:
        turns: list[Turn] = []
        last_prompt: int | None = None
        while True:
            reason = self._budget_left(started)
            if reason:
                return await self._force_finish(task, turns, reason, last_prompt)
            messages = self.context.build(task, turns, last_prompt)
            remaining = self.cfg.wall_clock_s - (self.clock() - started)
            try:
                resp = await asyncio.wait_for(self._call(messages, tools=True), timeout=max(1.0, remaining))
            except TimeoutError:
                return await self._force_finish(task, turns, "time", last_prompt)
            last_prompt = resp.usage.prompt_tokens or None
            self.stats.steps += 1
            calls = resp.tool_calls

            if not calls:
                parsed = _json_in(resp.content)
                if parsed is not None and "status" in parsed:
                    done = await self._accept_finish(parsed, messages + [assistant_message(resp)], resp)
                    if done:
                        return done
                self.stats.nudges += 1
                turns.append(Turn(assistant_message(resp), [{"role": "user", "content": NUDGE}],
                                  [{"role": "user", "content": NUDGE}]))
                continue

            finish = next((c for c in calls if c.name == "finish"), None)
            if finish is not None:
                done = await self._accept_finish(finish.arguments, messages + [assistant_message(resp)], resp)
                if done:
                    return done

            turn = Turn(assistant_message(resp))
            await self._execute(calls, turn)
            turns.append(turn)

    def _budget_left(self, started: float) -> str | None:
        if self.stats.steps >= self.cfg.max_steps:
            return "step"
        if self.stats.prompt_tokens >= self.cfg.max_input_tokens:
            return "input-token"
        if self.clock() - started >= self.cfg.wall_clock_s:
            return "time"
        return None

    async def _call(self, messages: list[dict[str, Any]], tools: bool, fmt: dict[str, Any] | None = None,
                    only_finish: bool = False) -> ModelResponse:
        schemas = [self._finish_schema] if only_finish else (self.tools.schemas() + [self._finish_schema] if tools else None)
        req = ChatRequest(messages=messages, tools=schemas, format=fmt, temperature=self.cfg.temperature,
                          think=self.cfg.think)
        with tracer.start_as_current_span("model call", attributes={"gen_ai.request.model": self.model.model_id}) as span:
            resp = await self.model.chat(req)
            self.stats.model_calls += 1
            self.stats.prompt_tokens += resp.usage.prompt_tokens
            self.stats.completion_tokens += resp.usage.completion_tokens
            self.stats.max_prompt_tokens = max(self.stats.max_prompt_tokens, resp.usage.prompt_tokens)
            self.stats.model_ms += resp.latency_ms
            span.set_attribute("gen_ai.usage.input_tokens", resp.usage.prompt_tokens)
            span.set_attribute("gen_ai.usage.output_tokens", resp.usage.completion_tokens)
            span.set_attribute("nightshift.latency_ms", round(resp.latency_ms, 1))
            span.set_attribute("nightshift.done_reason", resp.done_reason)
            span.set_attribute("nightshift.tool_calls", ",".join(c.name for c in resp.tool_calls))
        self.transcript.append({"type": "model", "step": self.stats.steps + 1, "content": resp.content[:2000],
                                "thinking_chars": len(resp.thinking),
                                "tool_calls": [{"name": c.name, "arguments": c.arguments} for c in resp.tool_calls],
                                "prompt_tokens": resp.usage.prompt_tokens, "completion_tokens": resp.usage.completion_tokens,
                                "latency_ms": round(resp.latency_ms, 1)})
        return resp

    async def _execute(self, calls: list[ToolCall], turn: Turn) -> None:
        limit = self.cfg.max_tool_calls_per_step
        runnable = [c for c in calls if c.name != "finish"][:limit]
        dropped = [c for c in calls if c.name != "finish"][limit:]

        async def one(call: ToolCall):
            with tracer.start_as_current_span(f"tool {call.name}", attributes={
                    "nightshift.tool": call.name, "nightshift.tool.args": json.dumps(call.arguments, default=str)[:500]}) as span:
                r = await self.tools.execute(call.name, call.arguments, self.ctx, self.board)
                span.set_attribute("nightshift.tool.result_chars", r.result_chars)
                if r.evidence_id:
                    span.set_attribute("nightshift.evidence_id", r.evidence_id)
                if r.error:
                    span.set_attribute("nightshift.tool.error", r.error)
                return r

        results = await asyncio.gather(*(one(c) for c in runnable))
        for r in results:
            self.stats.tool_calls += 1
            self.stats.tool_errors += int(r.error is not None)
            self.stats.write_attempts += int(r.write_attempt)
            turn.results.append(tool_message(r.tool, r.content))
            turn.compact.append(compact_result(r.tool, r.evidence_id, r.content))
            self.transcript.append({"type": "tool", "step": self.stats.steps, "name": r.tool, "arguments": r.arguments,
                                    "evidence_id": r.evidence_id, "error": r.error, "write_attempt": r.write_attempt,
                                    "result_chars": r.result_chars})
        for c in dropped:
            msg = f"ERROR: at most {limit} tool calls per step; '{c.name}' was not run. Call it again next step if needed."
            self.stats.tool_errors += 1
            turn.results.append(tool_message(c.name, msg))
            turn.compact.append(tool_message(c.name, msg))

    # --- finishing -----------------------------------------------------------------------------------
    def _validate(self, args: dict[str, Any]) -> tuple[dict[str, Any] | None, str | None]:
        try:
            report = self.report_model.model_validate(args)
        except ValidationError as e:
            return None, "\n".join(f"- {'.'.join(str(p) for p in err['loc']) or 'report'}: {err['msg']}" for err in e.errors())
        known = set(self.board.ids())
        cited = {i for f in getattr(report, "findings", []) for i in f.evidence_ids}
        unknown = sorted(cited - known)
        if unknown:
            return None, f"- findings cite evidence ids that do not exist: {unknown}. Known ids: {sorted(known) or 'none'}"
        return report.model_dump(mode="json"), None

    async def _accept_finish(self, args: dict[str, Any], messages: list[dict[str, Any]],
                             resp: ModelResponse, status: Status = "FINISHED") -> RunResult | None:
        report, errors = self._validate(args)
        if report is not None:
            return self._result(status, report)
        # One repair attempt with Ollama's JSON-schema format and no tools; then fail loudly.
        self.stats.repairs += 1
        repair_msgs = messages + [{"role": "user", "content": REPAIR.format(errors=errors)}]
        fixed = await self._call(repair_msgs, tools=False, fmt=self.report_model.model_json_schema())
        parsed = _json_in(fixed.content) or (fixed.tool_calls[0].arguments if fixed.tool_calls else None)
        if parsed is not None:
            report, errors2 = self._validate(parsed)
            if report is not None:
                return self._result(status, report)
            errors = errors2
        return self._result("FINISH_INVALID", None, error=f"finish arguments invalid after one repair:\n{errors}")

    async def _force_finish(self, task: str, turns: list[Turn], reason: str, last_prompt: int | None) -> RunResult:
        messages = self.context.build(task, turns, last_prompt) + [
            {"role": "user", "content": FORCE_FINISH.format(reason=reason)}]
        try:
            resp = await asyncio.wait_for(self._call(messages, tools=True, only_finish=True), FORCE_FINISH_TIMEOUT_S)
        except (TimeoutError, ModelError) as e:
            return self._partial(reason, f"forced finish failed: {e!r}")
        finish = next((c for c in resp.tool_calls if c.name == "finish"), None)
        args = finish.arguments if finish else _json_in(resp.content)
        if args is not None:
            report, _ = self._validate(args)
            if report is not None:
                return self._result("BUDGET_EXCEEDED", report, budget_reason=reason)
        return self._partial(reason, "forced finish did not produce a valid report")

    def _partial(self, reason: str, note: str) -> RunResult:
        """Harness-built report when the model cannot finish: no claims, just what was gathered."""
        ids = self.board.ids()
        report = {"status": "NO_ROOT_CAUSE",
                  "summary": f"Investigation stopped: {reason} budget exceeded before a report was produced. "
                             f"{len(ids)} pieces of evidence were collected ({', '.join(ids[:10])}).",
                  "root_cause": None, "findings": [], "hypotheses_not_confirmed": [], "remediation": None}
        return self._result("BUDGET_EXCEEDED", report, error=note, budget_reason=reason)

    def _result(self, status: Status, report: dict[str, Any] | None, error: str | None = None,
                budget_reason: str | None = None) -> RunResult:
        return RunResult(status=status, report=report, stats=self.stats, transcript=self.transcript, error=error,
                         budget_reason=budget_reason)
