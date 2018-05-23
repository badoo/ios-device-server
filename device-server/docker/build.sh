#!/bin/bash

set -eu

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "${DIR}")"

readonly GRADLE_HOME="${HOME}/.gradle"

docker run  \
    --rm \
    --name="device-server-build" \
    --workdir=/home/gradle/device-server \
    -v ${PROJECT_ROOT}:/home/gradle/device-server \
    -v ${GRADLE_HOME}:/home/gradle/.gradle \
    gradle:4.6.0-jdk9 \
    gradle clean build --no-daemon -g /home/gradle/.gradle --info
