{{- define "fs.name" -}}
{{- required "serviceName is required" .Values.serviceName -}}
{{- end -}}

{{- define "fs.labels" -}}
app.kubernetes.io/name: {{ include "fs.name" . }}
app.kubernetes.io/part-of: fraudshield
app.kubernetes.io/managed-by: {{ .Release.Service }}
fraudshield.io/environment: {{ .Values.global.environment | quote }}
{{- end -}}

{{- define "fs.selector" -}}
app.kubernetes.io/name: {{ include "fs.name" . }}
{{- end -}}

{{- define "fs.image" -}}
{{- $repo := default (printf "fraudshield-%s" .Values.serviceName) .Values.image.repository -}}
{{- printf "%s/%s:%s" (required "global.registry is required" .Values.global.registry) $repo (required "global.imageTag is required" .Values.global.imageTag) -}}
{{- end -}}

{{- define "fs.podTemplate" -}}
metadata:
  labels:
    {{- include "fs.labels" . | nindent 4 }}
    {{- if .Values.workloadIdentity }}
    azure.workload.identity/use: "true"
    {{- end }}
  annotations:
    checksum/config: {{ include (print .Template.BasePath "/configmap.yaml") . | sha256sum }}
    prometheus.io/scrape: "true"
    prometheus.io/port: {{ ternary .Values.ports.management .Values.ports.http (eq .Values.kind "java") | quote }}
    prometheus.io/path: {{ ternary "/actuator/prometheus" "/metrics" (eq .Values.kind "java") | quote }}
spec:
  serviceAccountName: {{ include "fs.name" . }}
  automountServiceAccountToken: {{ .Values.workloadIdentity }}
  nodeSelector:
    {{- toYaml .Values.nodeSelector | nindent 4 }}
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: topology.kubernetes.io/zone
      whenUnsatisfiable: ScheduleAnyway
      labelSelector:
        matchLabels:
          {{- include "fs.selector" . | nindent 10 }}
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    fsGroup: 10001
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: {{ include "fs.name" . }}
      image: {{ include "fs.image" . }}
      imagePullPolicy: {{ .Values.image.pullPolicy }}
      ports:
        - name: http
          containerPort: {{ .Values.ports.http }}
        {{- if eq .Values.kind "java" }}
        - name: management
          containerPort: {{ .Values.ports.management }}
        {{- end }}
      envFrom:
        - configMapRef:
            name: {{ include "fs.name" . }}
      resources:
        {{- toYaml .Values.resources | nindent 8 }}
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      volumeMounts:
        - name: tmp
          mountPath: /tmp
      {{- if eq .Values.kind "java" }}
      startupProbe:
        httpGet:
          path: {{ .Values.probes.startup }}
          port: management
        periodSeconds: 5
        failureThreshold: 60
      livenessProbe:
        httpGet:
          path: {{ .Values.probes.liveness }}
          port: management
        periodSeconds: 10
      readinessProbe:
        httpGet:
          path: {{ .Values.probes.readiness }}
          port: management
        periodSeconds: 5
      {{- else }}
      readinessProbe:
        httpGet:
          path: /healthz
          port: http
        periodSeconds: 5
      {{- end }}
  volumes:
    - name: tmp
      emptyDir: {}
{{- end -}}
