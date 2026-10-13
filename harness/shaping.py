"""Deterministic output shaping (spec §5.2): tools never hand raw dumps to the model. Results are
capped, sampled and summarised (counts, top signatures, min/max/percentiles, change points)."""

from __future__ import annotations

import re
import statistics
from collections import Counter, defaultdict
from datetime import UTC, datetime
from typing import Any

_UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I)
_HEX = re.compile(r"\b(?=[0-9a-f]*\d)[0-9a-f]{12,}\b", re.I)
_TS = re.compile(r"\b\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?\b")
_NUM = re.compile(r"(?<![A-Za-z])-?\d+(?:\.\d+)?(?![A-Za-z])")
_QUOTED = re.compile(r"'[^']{0,80}'")
_SPACES = re.compile(r"\s+")


def iso(ts: float | int | str) -> str:
    """Seconds (or ns/ms) since epoch -> ISO-8601 UTC, second precision."""
    t = float(ts)
    if t > 1e17:
        t /= 1e9
    elif t > 1e14:
        t /= 1e6
    elif t > 1e11:
        t /= 1e3
    return datetime.fromtimestamp(t, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def truncate(text: str, n: int) -> str:
    return text if len(text) <= n else text[: n - 1] + "…"


def log_signature(line: str, exception_type: str | None = None) -> str:
    """Template of a log line: variable parts (ids, numbers, timestamps) replaced by placeholders."""
    s = _TS.sub("<ts>", line)
    s = _UUID.sub("<uuid>", s)
    s = _HEX.sub("<hex>", s)
    s = _QUOTED.sub("'<str>'", s)
    s = _NUM.sub("<n>", s)
    s = _SPACES.sub(" ", s).strip()
    s = truncate(s, 160)
    return f"{exception_type}: {s}" if exception_type else s


# --- logs -------------------------------------------------------------------------------------------
def summarize_logs(entries: list[dict[str, Any]], total_matching: int | None, sample_lines: int,
                   top_n: int) -> dict[str, Any]:
    """entries: [{"ts": ns, "line": str, "labels": {...}}], newest first."""
    by_service: Counter[str] = Counter()
    by_level: Counter[str] = Counter()
    signatures: Counter[str] = Counter()
    example: dict[str, dict[str, Any]] = {}
    for e in entries:
        labels = e.get("labels", {})
        service = labels.get("service_name", "?")
        level = labels.get("detected_level") or labels.get("severity_text") or labels.get("level") or "?"
        by_service[service] += 1
        by_level[level.lower()] += 1
        sig = f"[{service}] " + log_signature(e["line"], labels.get("exception_type"))
        signatures[sig] += 1
        if sig not in example:
            example[sig] = e
    times = [e["ts"] for e in entries]
    top = []
    for sig, n in signatures.most_common(top_n):
        ex = example[sig]
        item: dict[str, Any] = {"signature": sig, "count": n, "example": truncate(ex["line"], 300)}
        exc = ex.get("labels", {}).get("exception_message")
        if exc:
            item["exception_message"] = truncate(exc, 300)
        top.append(item)
    return {
        "total_matching": total_matching if total_matching is not None else len(entries),
        "returned": len(entries),
        "time_range": {"first": iso(min(times)), "last": iso(max(times))} if times else None,
        "by_service": dict(by_service.most_common()),
        "by_level": dict(by_level.most_common()),
        "distinct_signatures": len(signatures),
        "top_signatures": top,
        "samples": [{"time": iso(e["ts"]), "service": e.get("labels", {}).get("service_name", "?"),
                     "line": truncate(e["line"], 300)} for e in entries[:sample_lines]],
    }


# --- metrics ----------------------------------------------------------------------------------------
_KEEP_LABELS = ("service_name", "http_route", "http_response_status_code", "http_request_method", "jvm_memory_type",
                "alertname", "state", "pool_name", "server_address", "service_version", "le")


def series_name(metric: dict[str, str]) -> str:
    keep = {k: v for k, v in metric.items() if k in _KEEP_LABELS or k == "__name__"}
    name = keep.pop("__name__", "")
    inner = ",".join(f'{k}="{v}"' for k, v in sorted(keep.items()))
    return f"{name}{{{inner}}}" if inner or name else "{}"


def _num(v: str) -> float | None:
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return None if x != x or x in (float("inf"), float("-inf")) else x


def change_point(values: list[float]) -> dict[str, Any] | None:
    """Single most significant level shift (difference of means before/after a split point)."""
    n = len(values)
    if n < 6:
        return None
    best_i, best_score = None, 0.0
    spread = statistics.pstdev(values) or 1e-9
    for i in range(3, n - 2):
        before, after = values[:i], values[i:]
        diff = statistics.fmean(after) - statistics.fmean(before)
        score = abs(diff) / spread
        if score > best_score:
            best_i, best_score = i, score
    if best_i is None or best_score < 1.0:
        return None
    before, after = values[:best_i], values[best_i:]
    return {"index": best_i, "mean_before": round(statistics.fmean(before), 6),
            "mean_after": round(statistics.fmean(after), 6), "score": round(best_score, 2)}


def _stats(vals: list[float]) -> dict[str, float]:
    s = sorted(vals)
    p95 = s[min(len(s) - 1, int(round(0.95 * (len(s) - 1))))]
    return {"min": round(s[0], 6), "max": round(s[-1], 6), "mean": round(statistics.fmean(s), 6), "p95": round(p95, 6)}


def summarize_range(result: list[dict[str, Any]], max_series: int) -> dict[str, Any]:
    """Prometheus matrix -> per-series stats and change point, largest-magnitude series first."""
    series = []
    for r in result:
        points = [(float(t), _num(v)) for t, v in r.get("values", [])]
        points = [(t, v) for t, v in points if v is not None]
        if not points:
            continue
        vals = [v for _, v in points]
        cp = change_point(vals)
        item: dict[str, Any] = {"series": series_name(r.get("metric", {})), **_stats(vals),
                                "first": round(vals[0], 6), "last": round(vals[-1], 6), "points": len(vals),
                                "from": iso(points[0][0]), "to": iso(points[-1][0])}
        if cp:
            item["change_point"] = {"at": iso(points[cp["index"]][0]), "mean_before": cp["mean_before"],
                                    "mean_after": cp["mean_after"], "score": cp["score"]}
        series.append(item)
    series.sort(key=lambda s: abs(s["max"]), reverse=True)
    return {"result_type": "matrix", "total_series": len(series), "series": series[:max_series],
            "truncated": len(series) > max_series}


def summarize_instant(result: list[dict[str, Any]], max_series: int) -> dict[str, Any]:
    rows = []
    for r in result:
        _, v = r.get("value", [0, None])
        x = _num(v)
        rows.append({"series": series_name(r.get("metric", {})), "value": None if x is None else round(x, 6)})
    rows.sort(key=lambda s: abs(s["value"] or 0), reverse=True)
    return {"result_type": "vector", "total_series": len(rows), "series": rows[:max_series],
            "truncated": len(rows) > max_series}


# --- traces -----------------------------------------------------------------------------------------
def summarize_search(traces: list[dict[str, Any]], limit: int) -> dict[str, Any]:
    """Tempo /api/search result -> counts per root service and slowest traces with ids."""
    by_root: Counter[str] = Counter()
    durations = []
    rows = []
    for t in traces:
        root = t.get("rootServiceName", "?")
        by_root[root] += 1
        d = float(t.get("durationMs") or 0)
        durations.append(d)
        start = t.get("startTimeUnixNano")
        rows.append({"trace_id": t.get("traceID"), "root": f'{root} {t.get("rootTraceName", "")}'.strip(),
                     "duration_ms": round(d, 1), "start": iso(start) if start else None})
    rows.sort(key=lambda r: r["duration_ms"], reverse=True)
    return {"traces_found": len(traces), "by_root_service": dict(by_root.most_common()),
            "duration_ms": _stats(durations) if durations else None, "slowest": rows[:limit]}


def _attr(value: dict[str, Any]) -> Any:
    for k in ("stringValue", "intValue", "doubleValue", "boolValue"):
        if k in value:
            return value[k]
    return None


def flatten_trace(trace: dict[str, Any]) -> list[dict[str, Any]]:
    """Tempo /api/traces/{id} (OTLP JSON) -> flat spans with the attributes that matter."""
    spans = []
    for batch in trace.get("batches", trace.get("resourceSpans", [])):
        res = {a["key"]: _attr(a["value"]) for a in batch.get("resource", {}).get("attributes", [])}
        service = res.get("service.name", "?")
        for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", [])):
            for s in scope.get("spans", []):
                attrs = {a["key"]: _attr(a["value"]) for a in s.get("attributes", [])}
                start, end = int(s.get("startTimeUnixNano", 0)), int(s.get("endTimeUnixNano", 0))
                status = (s.get("status") or {}).get("code")
                events = []
                for ev in s.get("events", []):
                    ea = {a["key"]: _attr(a["value"]) for a in ev.get("attributes", [])}
                    if ev.get("name") == "exception":
                        events.append(truncate(f'{ea.get("exception.type", "")}: {ea.get("exception.message", "")}', 300))
                spans.append({"service": service, "version": res.get("service.version"), "name": s.get("name"),
                              "span_id": s.get("spanId"), "parent_id": s.get("parentSpanId") or None,
                              "start_ns": start, "duration_ms": round((end - start) / 1e6, 2),
                              "error": status in (2, "STATUS_CODE_ERROR"), "attributes": attrs, "exceptions": events})
    spans.sort(key=lambda s: s["start_ns"])
    return spans


_SPAN_ATTRS = ("http.route", "http.response.status_code", "db.system", "db.statement", "db.operation",
               "server.address", "url.path", "http.request.method")


def summarize_trace(trace_id: str, spans: list[dict[str, Any]], max_spans: int = 25) -> dict[str, Any]:
    if not spans:
        return {"trace_id": trace_id, "spans": 0}
    t0 = spans[0]["start_ns"]
    total_ms = round((max(s["start_ns"] + s["duration_ms"] * 1e6 for s in spans) - t0) / 1e6, 1)
    per_service: dict[str, dict[str, Any]] = defaultdict(lambda: {"spans": 0, "errors": 0, "max_ms": 0.0})
    for s in spans:
        ps = per_service[s["service"]]
        ps["spans"] += 1
        ps["errors"] += int(s["error"])
        ps["max_ms"] = max(ps["max_ms"], s["duration_ms"])

    def compact(s: dict[str, Any]) -> dict[str, Any]:
        out = {"service": s["service"], "name": s["name"], "duration_ms": s["duration_ms"],
               "offset_ms": round((s["start_ns"] - t0) / 1e6, 1)}
        if s["error"]:
            out["error"] = True
        for k in _SPAN_ATTRS:
            if k in s["attributes"]:
                out[k] = truncate(str(s["attributes"][k]), 200)
        if s["exceptions"]:
            out["exceptions"] = s["exceptions"][:2]
        return out

    errors = [compact(s) for s in spans if s["error"]]
    slowest = [compact(s) for s in sorted(spans, key=lambda s: s["duration_ms"], reverse=True)[:5]]
    return {"trace_id": trace_id, "spans": len(spans), "duration_ms": total_ms,
            "services": {k: v for k, v in per_service.items()},
            "versions": sorted({f'{s["service"]}={s["version"]}' for s in spans if s["version"]}),
            "error_spans": errors[:8], "slowest_spans": slowest,
            "timeline": [compact(s) for s in spans[:max_spans]], "timeline_truncated": len(spans) > max_spans}
