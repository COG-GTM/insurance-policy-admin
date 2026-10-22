# Main branch protection

Apply the policy with an administrator account after the required checks have been published:

```sh
gh api -X PUT repos/COG-GTM/insurance-policy-admin/branches/main/protection \
  --input docs/dev/branch-protection-main.json
```

The policy requires strict status checks for the PAS platform and compatibility workflows, one
approving review, dismissal of stale reviews, and admin enforcement. Force pushes and branch
deletions are disabled. This change is pending because the GitHub App does not have repository
administration permission.
