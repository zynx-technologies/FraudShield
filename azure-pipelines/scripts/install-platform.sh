#!/usr/bin/env bash
# Installs cluster add-ons in every AKS cluster of an environment: the FraudShield namespace, internal
# ingress-nginx, Argo Rollouts, Argo CD (with this repository and the FraudShield ApplicationSet), and
# Prometheus for canary analysis. KEDA, workload identity, and the Key Vault CSI driver are AKS add-ons.
#
# Clusters are private and their egress allows only the shared registry and Microsoft registries, so
# third-party images are imported into the registry first, and a post-renderer points charts at it.
#
# Usage: install-platform.sh <environment>
# Needs: ACR_NAME, AKS_CLUSTERS, GITOPS_REPO_URL, GITOPS_REPO_TOKEN (read-only PAT), and helm, jq, yq.
set -euo pipefail

ENVIRONMENT="$1"
: "${ACR_NAME:?}" "${AKS_CLUSTERS:?}" "${GITOPS_REPO_URL:?}" "${GITOPS_REPO_TOKEN:?}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ACR="${ACR_NAME}.azurecr.io"

# Pinned chart versions. Update them together, test in dev, and promote like any other change.
declare -A CHARTS=(
  [ingress-nginx]="https://kubernetes.github.io/ingress-nginx ingress-nginx 4.11.3"
  [argo-rollouts]="https://argoproj.github.io/argo-helm argo-rollouts 2.38.0"
  [argo-cd]="https://argoproj.github.io/argo-helm argo-cd 7.7.11"
  [prometheus]="https://prometheus-community.github.io/helm-charts prometheus 25.27.0"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bundle/charts"

for release in "${!CHARTS[@]}"; do
  read -r repo chart version <<< "${CHARTS[$release]}"
  helm pull "$chart" --repo "$repo" --version "$version" --destination "$WORK/bundle/charts"
  mv "$WORK/bundle/charts/${chart}-${version}.tgz" "$WORK/bundle/charts/${release}.tgz"
  cp "$ROOT/deploy/platform/${release}.values.yaml" "$WORK/bundle/"

  # Import every image the chart uses into the shared registry, keeping the upstream path.
  helm template "$release" "$WORK/bundle/charts/${release}.tgz" -f "$ROOT/deploy/platform/${release}.values.yaml" \
    | grep -oE 'image: *"?[^" ]+' | sed -E 's/image: *"?//' | sort -u | while read -r image; do
      image="${image%@sha256:*}"
      [[ "$image" == *.azurecr.io/* || "$image" == mcr.microsoft.com/* ]] && continue
      [[ "$image" == */*/* || "$image" == *.*/* ]] || image="docker.io/library/$image"
      target="${image#docker.io/}"
      echo "Importing $image"
      az acr import --name "$ACR_NAME" --source "$image" --image "$target" --force --output none
    done
done

# Rewrite upstream registries to the shared registry at install time.
cat > "$WORK/bundle/post-render.sh" <<EOF
#!/bin/sh
sed -E 's#(image: *"?)(docker\.io/|quay\.io/|registry\.k8s\.io/|ghcr\.io/|public\.ecr\.aws/)#\1${ACR}/\2#'
EOF

cp "$ROOT/deploy/platform/namespace.yaml" "$WORK/bundle/"
cp "$ROOT/deploy/gitops/argocd/project.yaml" "$WORK/bundle/"

cat > "$WORK/bundle/repo-secret.yaml" <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: fraudshield-repo
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: ${GITOPS_REPO_URL}
  username: pipelines
  password: ${GITOPS_REPO_TOKEN}
EOF

for cluster in $AKS_CLUSTERS; do
  rg="${cluster%%/*}"; name="${cluster##*/}"
  # Find this cluster's region key ("primary" or "secondary") in the values on the releases branch.
  region=""; values=""
  for key in primary secondary; do
    file="$(git -C "$ROOT" show "origin/releases:environments/${ENVIRONMENT}/platform-${key}.yaml" 2>/dev/null || true)"
    if [[ "$(echo "$file" | yq '.global.clusterName')" == "$name" ]]; then region="$key"; values="$file"; fi
  done
  ingress_ip="$(echo "$values" | yq '.global.ingressIp')"
  ingress_subnet="$(echo "$values" | yq '.global.ingressSubnet')"
  [[ -n "$region" && -n "$ingress_ip" && "$ingress_ip" != "null" ]] || { echo "No platform values for $name. Run the infra pipeline first." >&2; exit 1; }

  sed -e "s#__ENV__#${ENVIRONMENT}#g" -e "s#__REGION__#${region}#g" -e "s#__REPO_URL__#${GITOPS_REPO_URL}#g" \
    "$ROOT/deploy/gitops/argocd/applicationset.yaml" > "$WORK/bundle/applicationset.yaml"
  sed -i -e "s#__REPO_URL__#${GITOPS_REPO_URL}#g" "$WORK/bundle/project.yaml"

  echo "Installing platform add-ons on $name ($region, ingress $ingress_ip)"
  az aks command invoke --resource-group "$rg" --name "$name" --file "$WORK/bundle" --command "
    set -e
    chmod +x post-render.sh
    kubectl apply -f namespace.yaml
    helm upgrade --install ingress-nginx charts/ingress-nginx.tgz -n ingress-nginx --create-namespace \
      -f ingress-nginx.values.yaml --post-renderer ./post-render.sh --wait \
      --set controller.service.loadBalancerIP=${ingress_ip} \
      --set-string controller.service.annotations.service\\.beta\\.kubernetes\\.io/azure-load-balancer-internal-subnet=${ingress_subnet}
    helm upgrade --install argo-rollouts charts/argo-rollouts.tgz -n argo-rollouts --create-namespace \
      -f argo-rollouts.values.yaml --post-renderer ./post-render.sh --wait
    helm upgrade --install prometheus charts/prometheus.tgz -n monitoring --create-namespace \
      -f prometheus.values.yaml --post-renderer ./post-render.sh --wait
    helm upgrade --install argo-cd charts/argo-cd.tgz -n argocd --create-namespace \
      -f argo-cd.values.yaml --post-renderer ./post-render.sh --wait
    kubectl apply -f repo-secret.yaml -f project.yaml -f applicationset.yaml
    kubectl get pods -A -l 'app.kubernetes.io/part-of in (argocd, argo-rollouts)'
  " --query logs -o tsv
done