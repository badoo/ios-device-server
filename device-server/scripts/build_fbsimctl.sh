#!/usr/bin/env bash

set -e
set -u

# We are using forked version of fbsimctl with some fixes, see link below

readonly REPOSITORY=https://github.com/NickAb/FBSimulatorControl.git
readonly REVISION=292a1bd # https://github.com/NickAb/FBSimulatorControl/commits/patched

readonly VERSION_NAME=HEAD-${REVISION}
readonly FBSIMCTL_BASE_PATH=/usr/local/Cellar/fbsimctl
readonly DEST_DIR=${FBSIMCTL_BASE_PATH}/${VERSION_NAME}
readonly DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
readonly WORKING_DIR=$(mktemp -d)
readonly BUILD_DIR=${WORKING_DIR}/build

clone() {
    echo "Checking out ${REPOSITORY}#${REVISION} at ${WORKING_DIR}"
    git clone ${REPOSITORY} "${WORKING_DIR}"
    git checkout ${REVISION}
}

build() {
    echo "Building fbsimctl to ${BUILD_DIR}"
    ./build.sh fbsimctl build "${BUILD_DIR}"
}

copy_to_cellar() {
    echo "Copying fbsimctl to Cellar ${DEST_DIR}"
    rm -rf ${DEST_DIR}
    mkdir -p ${DEST_DIR}
    cp -rp "${BUILD_DIR}/bin" ${DEST_DIR}/
    cp -rp "${BUILD_DIR}/Frameworks" ${DEST_DIR}/
    cp -rp "${BUILD_DIR}/info.plist" ${DEST_DIR}/
}

cleanup() {
    echo "Removing temp working directory"
    rm -rf "${WORKING_DIR}"
}

print_info() {
    echo ""
    echo "Built from ${REPOSITORY}#${REVISION}"
    echo "with $(xcodebuild -version)"
    echo "Results are at ${DEST_DIR}"
    echo ""
    echo "To set as default fbsimctl run:"
    echo "brew switch fbsimctl ${VERSION_NAME}"
    echo ""
}

cli_build() {
    pushd "${WORKING_DIR}"

    clone
    build
    copy_to_cellar
    cleanup
    print_info

    popd
}

cli_build
