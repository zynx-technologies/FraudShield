# FraudShield release state

Written only by GitHub Actions. Argo CD reads it:

- environments/<env>/release.yaml: image tag (App workflow)
- environments/<env>/model.yaml: model versions (Model release workflow)
- environments/<env>/platform-<region>.yaml: Terraform outputs (Infrastructure workflow)

Configuration lives on main and changes only through pull requests.
