{{- define "shepherd-adapter.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "shepherd-adapter.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "shepherd-adapter.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "shepherd-adapter.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/component: adapter
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "shepherd-adapter.selectorLabels" -}}
app.kubernetes.io/name: {{ include "shepherd-adapter.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "shepherd-adapter.secretName" -}}
{{- default (include "shepherd-adapter.fullname" .) .Values.secret.existingSecret -}}
{{- end -}}

{{- define "shepherd-adapter.claim" -}}
{{- default (include "shepherd-adapter.fullname" .) .Values.persistence.existingClaim -}}
{{- end -}}
