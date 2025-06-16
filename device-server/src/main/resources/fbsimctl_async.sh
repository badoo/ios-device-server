#!/bin/zsh

# /opt/homebrew/bin — Homebrew PATH on Macs with Apple silicon
# /usr/local/bin — Homebrew PATH on Macs with Intel CPU

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:${PATH}"

set -u
readonly UDID=${1}
readonly PORT=${2}

readonly FBSIMCTL_LOG=${3}
readonly FBSIMCTL_PID=${4}
readonly TMUX_SESSION_NAME="fbsimctl_${UDID}"

set -xe
/bin/test -f /usr/local/bin/tmux && TMUX_BIN="/usr/local/bin/tmux"
/bin/test -f /opt/homebrew/bin/tmux && TMUX_BIN="/opt/homebrew/bin/tmux"

/bin/test -f /usr/local/bin/fbsimctl && FBSIMCTL_BIN="/usr/local/bin/fbsimctl"
/bin/test -f /opt/homebrew/bin/fbsimctl && FBSIMCTL_BIN="/opt/homebrew/bin/fbsimctl"

/bin/test -f ${TMUX_BIN} || exit 33
/bin/test -f ${FBSIMCTL_BIN} || exit 35

${TMUX_BIN} kill-session -t "${TMUX_SESSION_NAME}" || true

${TMUX_BIN} new-session -d -s "${TMUX_SESSION_NAME}" \
bash -c "echo \$\$ > ${FBSIMCTL_PID}; \
    exec ${FBSIMCTL_BIN} \
            ${UDID} \
            listen \
            --http \
            ${PORT} \
            > \"${FBSIMCTL_LOG}\" 2>&1"
