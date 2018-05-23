#!/bin/bash

set -xe

export DEVICE_SERVER_JAR='build/libs/device-server-1.0-SNAPSHOT.jar'
export JAVA_HOME=$(/usr/libexec/java_home -v 10)

pushd "$( dirname "${BASH_SOURCE[0]}" )"

echo "Building Device Server"
./gradlew jar --no-daemon

./jar_launcher.sh