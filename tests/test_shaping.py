from harness import shaping


def test_log_signature_strips_variable_parts():
    a = shaping.log_signature("payment failed for order 31889: payments returned HTTP 504 at 2026-10-12T21:57:18Z")
    b = shaping.log_signature("payment failed for order 42: payments returned HTTP 502 at 2026-10-12T22:00:00Z")
    assert a == b == "payment failed for order <n>: payments returned HTTP <n> at <ts>"
    assert shaping.log_signature("trace 0cab32d709c4e7cf779103eb6458c24d", "java.lang.NullPointerException") \
        == "java.lang.NullPointerException: trace <hex>"


def test_summarize_logs_counts_and_caps():
    entries = [{"ts": 1791837760_000000000 + i, "line": f"payment failed for order {i}: HTTP 502",
                "labels": {"service_name": "orders", "detected_level": "error"}} for i in range(50)]
    entries += [{"ts": 1791837700_000000000, "line": "x" * 1000, "labels": {"service_name": "payments", "detected_level": "warn"}}]
    out = shaping.summarize_logs(entries, total_matching=900, sample_lines=5, top_n=3)
    assert out["total_matching"] == 900 and out["returned"] == 51
    assert out["by_service"] == {"orders": 50, "payments": 1}
    assert out["top_signatures"][0]["count"] == 50
    assert len(out["samples"]) == 5 and all(len(s["line"]) <= 300 for s in out["samples"])


def test_change_point_finds_a_level_shift():
    values = [0.01] * 10 + [0.35] * 10
    cp = shaping.change_point(values)
    assert cp["index"] == 10 and cp["mean_before"] == 0.01 and cp["mean_after"] == 0.35
    assert shaping.change_point([0.01] * 20) is None


def test_summarize_range_sorts_by_magnitude_and_truncates():
    def series(svc, vals):
        return {"metric": {"service_name": svc, "instance": "x"}, "values": [[1791837000 + 15 * i, str(v)] for i, v in enumerate(vals)]}

    out = shaping.summarize_range([series("gateway", [0.0] * 8), series("payments", [0.01] * 4 + [0.4] * 4),
                                   series("orders", [0.2] * 8)], max_series=2)
    assert out["total_series"] == 3 and out["truncated"]
    assert out["series"][0]["series"] == '{service_name="payments"}'
    assert out["series"][0]["max"] == 0.4 and "change_point" in out["series"][0]
    assert "instance" not in out["series"][0]["series"]


def test_iso_handles_seconds_and_nanoseconds():
    assert shaping.iso(1791837760) == shaping.iso(1791837760_000000000) == "2026-10-12T20:42:40Z"
