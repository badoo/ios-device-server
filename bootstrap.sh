#!/usr/bin/env bash

set -e
set -u

echo "Building fbsimctl..."
./device-server/scripts/build_fbsimctl.sh

echo "Building WebDriverAgent..."

# Building WebDriverAgent for devices is skipped by default, because it requires signing patch to set developer team and bundle id.
export NO_DEVICE_BUILD=${NO_DEVICE_BUILD:-1}

./device-server/scripts/update_wda.sh

echo "Checking Java Version"
/usr/libexec/java_home -v 10 -F
