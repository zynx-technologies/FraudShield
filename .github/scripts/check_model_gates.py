"""Checks a model version against the FraudShield promotion gates (design section 8) before it is shadowed
or released. Evidence is read from Unity Catalog model version tags, which the Databricks validation,
fairness, performance, and shadow jobs write. Nothing here can be overridden by a pipeline parameter.

Usage:
  python check_model_gates.py --model fraudshield_prod.models.fs_txn --version 15 --stage shadow
  python check_model_gates.py --model fraudshield_prod.models.fs_txn --version 15 --stage release

Stages:
  shadow   validation, fairness and explainability, performance
  release  all of the above, plus 14 days of stable shadow and an approval by someone other than the author

Needs DATABRICKS_HOST and Databricks authentication (the pipeline uses azure-cli auth), and mlflow-skinny.
"""
import argparse
import json
import os
import sys

from mlflow import MlflowClient

SHADOW_DAYS = 14
P99_LIMIT_MS = 15.0


def number(tags, key):
    try:
        return float(tags[key])
    except (KeyError, ValueError):
        return None


def check(version, stage):
    tags = version.tags or {}
    results = []

    def gate(name, passed, evidence):
        results.append({"gate": name, "passed": bool(passed), "evidence": evidence})

    # Validation: beats the champion on recall at the fixed alert rate, with no worse PR-AUC.
    recall, champ_recall = number(tags, "validation.recall_at_alert_rate"), number(tags, "validation.champion_recall_at_alert_rate")
    pr_auc, champ_pr_auc = number(tags, "validation.pr_auc"), number(tags, "validation.champion_pr_auc")
    gate("validation",
         None not in (recall, champ_recall, pr_auc, champ_pr_auc) and recall > champ_recall and pr_auc >= champ_pr_auc,
         f"recall {recall} vs champion {champ_recall}; PR-AUC {pr_auc} vs champion {champ_pr_auc}")

    # Fairness and explainability: group differences within limits, reason codes reviewed, model card.
    gate("fairness",
         tags.get("fairness.status") == "passed",
         f"fairness.status={tags.get('fairness.status')}")
    gate("explainability",
         tags.get("explainability.reason_codes_reviewed") == "true" and bool(tags.get("model_card.url")),
         f"reason codes reviewed={tags.get('explainability.reason_codes_reviewed')}; model card={tags.get('model_card.url')}")

    # Performance: inference p99 under 15 ms and memory within limit, from the load test.
    p99 = number(tags, "performance.inference_p99_ms")
    gate("performance",
         p99 is not None and p99 < P99_LIMIT_MS and tags.get("performance.memory_within_limit") == "true",
         f"inference p99 {p99} ms (limit {P99_LIMIT_MS}); memory ok={tags.get('performance.memory_within_limit')}")

    # The signed ONNX file must have been registered. The service verifies the signature when it loads.
    gate("signed artifact",
         bool(tags.get("onnx.sha256")) and bool(tags.get("onnx.signature_key_id")),
         f"sha256={tags.get('onnx.sha256')}; key={tags.get('onnx.signature_key_id')}")

    if stage == "release":
        days = number(tags, "shadow.stable_days")
        gate("shadow",
             days is not None and days >= SHADOW_DAYS and tags.get("shadow.feature_issues") == "0",
             f"stable days {days} (minimum {SHADOW_DAYS}); feature issues={tags.get('shadow.feature_issues')}")

        approver = (tags.get("approval.approved_by") or "").strip().lower()
        author = (version.user_id or "").strip().lower()
        gate("approval",
             approver and approver != author,
             f"approved by {approver or 'nobody'}; author {author}")

    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Unity Catalog name, catalog.schema.model")
    parser.add_argument("--version", required=True)
    parser.add_argument("--stage", choices=["shadow", "release"], required=True)
    args = parser.parse_args()

    client = MlflowClient(registry_uri="databricks-uc")
    version = client.get_model_version(args.model, args.version)
    results = check(version, args.stage)

    for r in results:
        mark = "PASS" if r["passed"] else "FAIL"
        print(f"[{mark}] {r['gate']}: {r['evidence']}")

    # Evidence file, published by the pipeline: Azure Pipelines staging directory or GitHub runner temp.
    summary = os.environ.get("BUILD_ARTIFACTSTAGINGDIRECTORY") or os.environ.get("RUNNER_TEMP")
    if summary:
        with open(os.path.join(summary, "model-gates.json"), "w", encoding="utf-8") as f:
            json.dump({"model": args.model, "version": args.version, "stage": args.stage, "results": results}, f, indent=2)

    failed = [r["gate"] for r in results if not r["passed"]]
    if failed:
        message = f"Model {args.model} v{args.version} failed: {', '.join(failed)}"
        print(f"::error::{message}" if os.environ.get("GITHUB_ACTIONS") else f"##vso[task.logissue type=error]{message}")
        sys.exit(1)
    print(f"Model {args.model} v{args.version} passed every {args.stage} gate.")


if __name__ == "__main__":
    main()
