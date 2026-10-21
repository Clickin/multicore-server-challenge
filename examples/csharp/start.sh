#!/usr/bin/env bash
# Foreground server (systemd ExecStart). Reads DATABASE_URL, JWT_SECRET, HOST, PORT, EXTRA_PORT, DB_POOL from the environment.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export ASPNETCORE_ENVIRONMENT="${ASPNETCORE_ENVIRONMENT:-Production}"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
# GC heap hard limit = 70% of RAM (documented GC setting, the .NET counterpart of Java's -XX:MaxRAMPercentage=70;
# .NET already applies 75% by default inside a memory-limited container, but not on a plain VM). DOTNET_GC* values are hex: 0x46 = 70.
export DOTNET_GCHeapHardLimitPercent="${DOTNET_GCHeapHardLimitPercent:-0x46}"
cd "$HERE/out"   # content root, so appsettings.json is found
exec dotnet FeedApi.dll
