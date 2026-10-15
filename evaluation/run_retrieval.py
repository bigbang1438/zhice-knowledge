"""Evaluate the local HTTP retriever, or compact an existing observed report.

The fixed published examples measure retrieval only. They are not a blind set or
an estimate of model-answer quality. Reports replace repeated source bodies with
SHA-256 and the exact gold-substring match used by the ranking calculation.
"""

from pathlib import Path
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import os
import time
import uuid
from urllib.parse import urlencode
from urllib.request import (
    HTTPRedirectHandler,
    HTTPCookieProcessor,
    ProxyHandler,
    Request,
    build_opener,
)

ROOT = Path(__file__).resolve().parent.parent
FORMAT = "zhice-retrieval-compact-v1"
MODES = ("bm25", "vector", "hybrid")
SOURCE_FIELDS = (
    "id",
    "documentId",
    "filename",
    "page",
    "lineStart",
    "lineEnd",
    "score",
)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def summarize(rows):
    positives = [row for row in rows if row["gold_file"]]
    negatives = [row for row in rows if not row["gold_file"]]
    return {
        "recall_at_1": sum(row["first_relevant_rank"] == 1 for row in positives)
        / len(positives),
        "recall_at_3": sum(
            row["first_relevant_rank"] is not None and row["first_relevant_rank"] <= 3
            for row in positives
        )
        / len(positives),
        "mrr_at_5": sum(
            1 / row["first_relevant_rank"] if row["first_relevant_rank"] else 0
            for row in positives
        )
        / len(positives),
        "negative_no_result": sum(row["no_result"] for row in negatives),
        "negative_total": len(negatives),
    }


def compact_report(raw, dataset, provenance):
    """Keep observed ranks, scores, timings and failures; replace source text only."""
    if raw["dataset"] != dataset["name"]:
        raise ValueError("Observed report and published dataset names differ")
    cases = {case["id"]: case for case in dataset["cases"]}
    chunks = {}
    modes = {}
    for mode in MODES:
        observed = raw["modes"][mode]
        rows = []
        if [row["id"] for row in observed["cases"]] != list(cases):
            raise ValueError("Observed report is missing or reordering published cases")
        for row in observed["cases"]:
            case = cases[row["id"]]
            if row["question"] != case["question"] or row["gold_file"] != case["file"]:
                raise ValueError(
                    "Observed question or gold filename differs from the published fixture"
                )
            sources = []
            for source in row["sources"]:
                item = {field: source[field] for field in SOURCE_FIELDS}
                item["text_sha256"] = sha256(source["text"].encode("utf-8"))
                item["gold_matched"] = bool(
                    case["file"]
                    and source["filename"] == case["file"]
                    and case["gold"] in source["text"]
                )
                sources.append(item)
                manifest = {
                    key: value
                    for key, value in item.items()
                    if key not in ("score", "gold_matched")
                }
                if source["id"] in chunks and chunks[source["id"]] != manifest:
                    raise ValueError("A source changed within the observed evaluation")
                chunks[source["id"]] = manifest
            relevant = [
                index + 1
                for index, source in enumerate(sources)
                if source["gold_matched"]
            ]
            if row["first_relevant_rank"] != (min(relevant) if relevant else None):
                raise ValueError(
                    "Stored rank differs from the preserved gold-substring evidence"
                )
            if row["no_result"] != (not sources):
                raise ValueError(
                    "Stored no-result flag differs from the observed sources"
                )
            rows.append(
                {
                    **{key: value for key, value in row.items() if key != "sources"},
                    "sources": sources,
                }
            )
        metrics = summarize(rows)
        if any(observed[key] != value for key, value in metrics.items()):
            raise ValueError(
                "Observed summary metrics differ from the preserved rankings"
            )
        modes[mode] = {**metrics, "cases": rows}
    documents = []
    for path in sorted((ROOT / "examples").glob("*.md")):
        observed_chunks = [
            chunk for chunk in chunks.values() if chunk["filename"] == path.name
        ]
        documents.append(
            {
                "filename": path.name,
                "file_sha256": sha256(path.read_bytes()),
                "bytes": path.stat().st_size,
                "chunk_count": len(observed_chunks),
            }
        )
        # Current published fixtures are each shorter than one parser chunk.
        expected_text = (
            path.read_text(encoding="utf-8-sig")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .rstrip()
        )
        if len(observed_chunks) != 1 or observed_chunks[0]["text_sha256"] != sha256(
            expected_text.encode("utf-8")
        ):
            raise ValueError(
                "Observed source does not match the current one-chunk example fixture"
            )
    if len(documents) != 4 or len(chunks) != 4:
        raise ValueError(
            "This published evaluation is restricted to exactly four documents and four chunks"
        )
    return {
        "format": FORMAT,
        "dataset": raw["dataset"],
        "dataset_sha256": sha256((ROOT / "evaluation/questions.json").read_bytes()),
        "scope": raw["scope"],
        "answerable": sum(bool(case["file"]) for case in cases.values()),
        "negative": sum(not case["file"] for case in cases.values()),
        "corpus": {
            "document_count": len(documents),
            "chunk_count": len(chunks),
            "documents": documents,
            "chunks": sorted(chunks.values(), key=lambda chunk: chunk["filename"]),
        },
        "modes": modes,
        "model_configuration": raw["model_configuration"],
        "provenance": provenance,
    }


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def evaluate(args, dataset):
    if args.url != "http://127.0.0.1:8093":
        raise SystemExit("This runner only targets the disposable local port 8093 demo")
    password = os.environ.get("ZHICE_EVAL_PASSWORD")
    if not password:
        raise SystemExit("Set ZHICE_EVAL_PASSWORD; do not store credentials in source")
    http = build_opener(
        ProxyHandler({}), HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect()
    )
    csrf = {}

    def request(path, method="GET", body=None, content_type=None):
        headers = {}
        if csrf and method != "GET":
            headers[csrf["headerName"]] = csrf["token"]
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        with http.open(
            Request(args.url + path, data=body, method=method, headers=headers),
            timeout=120,
        ) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    csrf.update(request("/api/session")["csrf"])
    request(
        "/api/login",
        "POST",
        urlencode({"username": args.username, "password": password}).encode(),
        "application/x-www-form-urlencoded",
    )
    csrf.update(request("/api/session")["csrf"])
    prefix = f"/api/spaces/{args.space}"
    documents = request(prefix + "/documents")["items"]
    expected_names = {path.name for path in (ROOT / "examples").glob("*.md")}
    if any(document["filename"] not in expected_names for document in documents):
        raise SystemExit(
            "Use a disposable space containing only the four published examples"
        )
    existing = {document["filename"] for document in documents}
    for path in sorted((ROOT / "examples").glob("*.md")):
        if path.name in existing:
            continue
        boundary = "ZhiceEvaluation" + uuid.uuid4().hex
        body = (
            (
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: text/markdown\r\n\r\n'
            ).encode()
            + path.read_bytes()
            + f"\r\n--{boundary}--\r\n".encode()
        )
        document = request(
            prefix + "/documents",
            "POST",
            body,
            "multipart/form-data; boundary=" + boundary,
        )
        print(
            json.dumps(
                {"imported": path.name, "chunks": document["chunkCount"]},
                ensure_ascii=True,
            ),
            flush=True,
        )
    documents = request(prefix + "/documents")["items"]
    if (
        len(documents) != 4
        or sum(document["chunkCount"] for document in documents) != 4
    ):
        raise SystemExit(
            "Expected exactly four current documents and four chunks; use a fresh demo database"
        )
    reports = {}
    for mode in MODES:
        rows = []
        for case in dataset["cases"]:
            begin = time.monotonic()
            result = request(
                prefix
                + "/search?"
                + urlencode({"q": case["question"], "mode": mode, "limit": 5})
            )
            sources = result["sources"]
            ranks = [
                index + 1
                for index, source in enumerate(sources)
                if case["file"]
                and source["filename"] == case["file"]
                and case["gold"] in source["text"]
            ]
            rows.append(
                {
                    "id": case["id"],
                    "question": case["question"],
                    "gold_file": case["file"],
                    "first_relevant_rank": min(ranks) if ranks else None,
                    "no_result": not sources,
                    "elapsed_seconds": round(time.monotonic() - begin, 3),
                    "sources": sources,
                }
            )
        reports[mode] = {**summarize(rows), "cases": rows}
        print(json.dumps({"mode": mode, **summarize(rows)}), flush=True)
    raw = {
        "dataset": dataset["name"],
        "scope": dataset["scope"],
        "modes": reports,
        "model_configuration": request("/api/session")["models"],
    }
    return compact_report(
        raw,
        dataset,
        {
            "method": "live_local_http",
            "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
            "source_text_storage": "Repeated UTF-8 source text replaced by SHA-256 and exact gold-substring match; rankings, scores, timings and negative results retained.",
        },
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8093")
    parser.add_argument("--username", default="demo_anna")
    parser.add_argument("--space", default="team-guide")
    parser.add_argument("--output", default="evaluation/retrieval-report.json")
    parser.add_argument(
        "--compact-existing",
        type=Path,
        help="Compact a previously observed raw report offline; no HTTP request or password required",
    )
    args = parser.parse_args()
    dataset = json.loads(
        (ROOT / "evaluation/questions.json").read_text(encoding="utf-8")
    )
    if args.compact_existing:
        original = args.compact_existing.read_bytes()
        report = compact_report(
            json.loads(original),
            dataset,
            {
                "method": "compacted_existing_observed_http_report",
                "raw_report_sha256": sha256(original),
                "source_text_storage": "Repeated UTF-8 source text replaced by SHA-256 and exact gold-substring match; all observed ranks, scores, timings and failures retained. No network rerun or metric adjustment.",
            },
        )
    else:
        report = evaluate(args, dataset)
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "output": str(output),
                "documents": report["corpus"]["document_count"],
                "chunks": report["corpus"]["chunk_count"],
            }
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
