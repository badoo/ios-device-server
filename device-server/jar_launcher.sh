#!/bin/bash

set -xe

declare -r DEVICE_SERVER_CONFIG_PATH="${DEVICE_SERVER_CONFIG_PATH}"
declare -r DEVICE_SERVER_JAR="${DEVICE_SERVER_JAR}"

declare -r WDA_RUNNER='../ios/facebook/simulators/WebDriverAgentRunner-Runner.app'
declare -r WDA_DEVICE_RUNNER='../ios/facebook/devices/WebDriverAgentRunner-Runner.app'
declare -r LOG_CONFIG='logback-test.xml'

export JAVA_HOME=$(/usr/libexec/java_home -v 10)

pushd "$( dirname "${BASH_SOURCE[0]}" )"

exec java \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:InitiatingHeapOccupancyPercent=45 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -Dlogback.configurationFile=${LOG_CONFIG} \
    -Ddevice.server.config.path=${DEVICE_SERVER_CONFIG_PATH} \
    -Dwda.bundle.path=${WDA_RUNNER} \
    -Dwda.device.bundle.path=${WDA_DEVICE_RUNNER} \
    -jar ${DEVICE_SERVER_JAR}
