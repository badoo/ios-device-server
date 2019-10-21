#!/bin/bash

set -xe

declare -r DEVICE_SERVER_CONFIG_PATH="${DEVICE_SERVER_CONFIG_PATH}"
declare -r DEVICE_SERVER_JAR="${DEVICE_SERVER_JAR:?Jar file is required}"

declare -r WDA_RUNNER=${DEVICE_SERVER_WDA_SIMULATOR_RUNNER:-'../ios/facebook/simulators/WebDriverAgentRunner-Runner.app'}
declare -r WDA_DEVICE_RUNNER=${DEVICE_SERVER_WDA_DEVICE_RUNNER:-'../ios/facebook/devices/WebDriverAgentRunner-Runner.app'}
declare -r FBSIMCTL_VERSION=${DEVICE_SERVER_FBSIMCTL_VERSION:-'HEAD-d30c2a73'}
declare -r LOG_CONFIG=${DEVICE_SERVER_LOG_CONFIG:-'logback-test.xml'}
declare -r NETTY_WORKER_GROUP_SIZE=${NETTY_WORKER_GROUP_SIZE:-''}
declare -r NETTY_CALL_GROUP_SIZE=${NETTY_CALL_GROUP_SIZE:-''}
declare -r VIDEO_RECORDER=${VIDEO_RECORDER:-'com.badoo.automation.deviceserver.ios.simulator.video.MJPEGVideoRecorder'}
declare -r SIMULATOR_WDA_CLASS=${SIMULATOR_WDA_CLASS:-'com.badoo.automation.deviceserver.ios.proc.SimulatorWebDriverAgent'}
export JAVA_HOME=$(/usr/libexec/java_home -v 10 -F || /usr/libexec/java_home -v 9 -F)

pushd "$( dirname "${BASH_SOURCE[0]}" )"

exec java \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:InitiatingHeapOccupancyPercent=45 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -Dlogback.configurationFile=${LOG_CONFIG} \
    -Ddevice.server.config.path=${DEVICE_SERVER_CONFIG_PATH} \
    -Dwda.bundle.path=${WDA_RUNNER} \
    -Dwda.device.bundle.path=${WDA_DEVICE_RUNNER} \
    -Dfbsimctl.version=${FBSIMCTL_VERSION} \
    -Dembedded.netty.workerGroupSize=${NETTY_WORKER_GROUP_SIZE} \
    -Dembedded.netty.callGroupSize=${NETTY_CALL_GROUP_SIZE} \
    -Dvideo.recorder=${VIDEO_RECORDER} \
    -Dsimulator.wda=${SIMULATOR_WDA_CLASS} \
    -jar ${DEVICE_SERVER_JAR}
