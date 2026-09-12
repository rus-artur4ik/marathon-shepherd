{{- define "marathon-shepherd.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "marathon-shepherd.fullname" -}}
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

{{- define "marathon-shepherd.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "marathon-shepherd.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/component: manager
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "marathon-shepherd.selectorLabels" -}}
app.kubernetes.io/name: {{ include "marathon-shepherd.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "marathon-shepherd.configSecret" -}}
{{- default (include "marathon-shepherd.fullname" .) .Values.config.existingSecret -}}
{{- end -}}

{{- define "marathon-shepherd.claim" -}}
{{- default (include "marathon-shepherd.fullname" .) .Values.persistence.existingClaim -}}
{{- end -}}
