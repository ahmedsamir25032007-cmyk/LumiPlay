#!/usr/bin/env sh
DEFAULT_JVM_OPTS=""
GRADLE_APP_BASE_NAME=${0##*/}
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec gradle "$@"
