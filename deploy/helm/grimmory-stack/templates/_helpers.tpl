{{- define "grimmory-stack.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "grimmory-stack.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
grimmory-stack-secrets
{{- end -}}
{{- end -}}

{{- define "grimmory-stack.labels" -}}
app.kubernetes.io/name: {{ include "grimmory-stack.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "grimmory-stack.selectorLabels" -}}
app.kubernetes.io/name: {{ include "grimmory-stack.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "grimmory-stack.imagePullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
{{- range . }}
  - name: {{ . | quote }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "grimmory-stack.keelAnnotations" -}}
{{- if .Values.global.keel.enabled }}
keel.sh/policy: {{ .Values.global.keel.policy | quote }}
keel.sh/trigger: {{ .Values.global.keel.trigger | quote }}
keel.sh/pollSchedule: {{ .Values.global.keel.pollSchedule | quote }}
keel.sh/match-tag: {{ .Values.global.keel.matchTag | quote }}
{{- end }}
{{- end -}}

{{- define "grimmory-stack.claimName" -}}
{{- . -}}
{{- end -}}
