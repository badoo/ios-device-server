#!/bin/zsh

# /opt/homebrew/bin — Homebrew PATH on Macs with Apple silicon
# /usr/local/bin — Homebrew PATH on Macs with Intel CPU


set -u
readonly XCTESTRUN_FILE=${1}
readonly UDID=${2}
readonly DERIVED_DATA_DIR=${3}
readonly XCRUN_SIMCTL_LOG=${4}
readonly XCRUN_SIMCTL_PID=${5}
readonly XCTESTRUN_FILENAME=$(basename "${XCTESTRUN_FILE}")
readonly TMUX_SESSION_NAME="DeviceAgent_${UDID}_${XCTESTRUN_FILENAME}"

set -xe

/bin/test -f /usr/local/bin/tmux && TMUX_BIN="/usr/local/bin/tmux"
/bin/test -f /opt/homebrew/bin/tmux && TMUX_BIN="/opt/homebrew/bin/tmux"

/bin/test -f ${TMUX_BIN} || exit 33

${TMUX_BIN} kill-session -t "${TMUX_SESSION_NAME}" || true

${TMUX_BIN} new-session -d -s "${TMUX_SESSION_NAME}" \
bash -c "echo \$\$ > ${XCRUN_SIMCTL_PID}; \
      exec /usr/bin/xcodebuild \
          test-without-building \
          -xctestrun \"${XCTESTRUN_FILE}\" \
          -destination \"id=${UDID}\" \
          -derivedDataPath \"${DERIVED_DATA_DIR}\" \
              > \"${XCRUN_SIMCTL_LOG}\" 2>&1"
