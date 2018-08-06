#!/usr/bin/env bash

set -e
set -u

readonly SIGNING_PATCH=${SIGNING_PATCH:-}
readonly NO_DEVICE_BUILD=${NO_DEVICE_BUILD:-0}

readonly REPOSITORY=https://github.com/facebook/WebDriverAgent.git
readonly REVISION=e92bce2
readonly DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
readonly WORKING_DIR=$(mktemp -d)
readonly BASE_DEST=${DIR}/../../ios/facebook
readonly SIMULATOR_DEST=${BASE_DEST}/simulators
readonly DEVICE_DEST=${BASE_DEST}/devices
readonly VERSION_FILE=${BASE_DEST}/version.txt


cleanup() {
    rm -rf "${WORKING_DIR}"
}

finally () {
  popd
}

clone_wda() {
    echo "Checking out ${REPOSITORY}#${REVISION} at ${WORKING_DIR}"

    git clone ${REPOSITORY} "${WORKING_DIR}"
    git checkout ${REVISION}
    ./Scripts/bootstrap.sh
}

patch_wda() {
    git status
    git apply "${SIGNING_PATCH}"
    git status
}

update_wda_for_simulator() {
    echo "Building WebDriverAgent for Simulators"
    xcodebuild -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner -destination 'generic/platform=iOS Simulator' -derivedDataPath build clean build-for-testing

    rm -rf "${SIMULATOR_DEST}"
    mkdir -p "${SIMULATOR_DEST}"

    cp -r "${WORKING_DIR}/build/Build/Products/Debug-iphonesimulator/WebDriverAgentRunner-Runner.app" "${SIMULATOR_DEST}/WebDriverAgentRunner-Runner.app"
}

update_wda_for_device() {
    echo "Building WebDriverAgent for Devices"
    xcodebuild -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner -destination 'generic/platform=iOS' -derivedDataPath build clean build-for-testing

    rm -rf "${DEVICE_DEST}"
    mkdir -p "${DEVICE_DEST}"

    cp -r "${WORKING_DIR}/build/Build/Products/Debug-iphoneos/WebDriverAgentRunner-Runner.app" "${DEVICE_DEST}/WebDriverAgentRunner-Runner.app"
}

write_version_file() {
    XCODE_VERSION=$(xcodebuild -version)
    cat > "${VERSION_FILE}" << EOL
commit ${REVISION}
built with ${XCODE_VERSION}

EOL
}

cli_update() {
    mkdir -p "${WORKING_DIR}"
    pushd "${WORKING_DIR}"

    clone_wda

    if [[ "${NO_DEVICE_BUILD}" -eq 1 ]]; then
        echo "Skipping build for devices";
    else
        patch_wda
        update_wda_for_device
    fi;

    update_wda_for_simulator

    write_version_file
    cleanup

    popd
}

cli_update
