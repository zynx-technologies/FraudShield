#!/usr/bin/env bash
# Logging, outputs, and summaries that work in both Azure Pipelines and GitHub Actions.
# Source it: . "$(dirname "$0")/ci.sh"

ci_is_github() { [[ -n "${GITHUB_ACTIONS:-}" ]]; }

ci_error() {
  if ci_is_github; then echo "::error::$*"; else echo "##vso[task.logissue type=error]$*"; fi
}

ci_warning() {
  if ci_is_github; then echo "::warning::$*"; else echo "##vso[task.logissue type=warning]$*"; fi
}

# ci_set_output <name> <value>: a step output other jobs can read.
ci_set_output() {
  if ci_is_github; then
    echo "$1=$2" >> "$GITHUB_OUTPUT"
  else
    echo "##vso[task.setvariable variable=$1;isOutput=true]$2"
    echo "##vso[task.setvariable variable=$1]$2"
  fi
}

# ci_summary <markdown-file>: shown on the run's summary page.
ci_summary() {
  if ci_is_github; then cat "$1" >> "$GITHUB_STEP_SUMMARY"; else echo "##vso[task.uploadsummary]$1"; fi
}

# A label for commit messages that links back to the run.
ci_run_label() {
  if ci_is_github; then
    echo "run ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
  else
    echo "build ${BUILD_BUILDNUMBER:-local}"
  fi
}
