# Main branch protection

The Devin GitHub App cannot apply required checks: the request returned HTTP 403, `Resource not accessible by integration`, because it has no repository admin permission. After PAS platform CI has run once, a repository admin should apply the checked-in payload:

```sh
gh api -X PUT repos/COG-GTM/insurance-policy-admin/branches/main/protection \
  -H "Accept: application/vnd.github+json" --input docs/dev/branch-protection-main.json
```

The first four required checks (`pas-platform / build-test`, `pas-platform / static-analysis`, `pas-platform / dependency-scan`, `pas-platform / container-image`) come from `.github/workflows/pas-platform-ci.yml`. Their `pas-platform /` prefix distinguishes them from the similarly named compatibility-gate checks. The remaining checks (`Schema, layouts & security`, `Build & tests`, `REST API contract`) are job names from `.github/workflows/compat-gate.yml`.

Admin enforcement and signed commits are intentionally off. To verify the configured checks:

```sh
gh api repos/COG-GTM/insurance-policy-admin/branches/main/protection --jq '.required_status_checks.checks[].context'
```
