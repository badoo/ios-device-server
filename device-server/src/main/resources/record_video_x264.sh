#!/bin/zsh

# /opt/homebrew/bin — Homebrew PATH on Macs with Apple silicon
# /usr/local/bin — Homebrew PATH on Macs with Intel CPU

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:${PATH}"

set -u
readonly UDID=${1}
readonly URL=${2}
readonly RECORDING=${3}
readonly RECORDING_LOG=${4}
readonly RECORDING_PID=${5}
readonly TMUX_SESSION_NAME="ffmpeg_${UDID}"

set -xe

/bin/test -f /usr/local/bin/tmux && TMUX_BIN="/usr/local/bin/tmux"
/bin/test -f /opt/homebrew/bin/tmux && TMUX_BIN="/opt/homebrew/bin/tmux"
/bin/test -f /usr/local/bin/ffmpeg && FFMPEG_BIN="/usr/local/bin/ffmpeg"
/bin/test -f /opt/homebrew/bin/ffmpeg && FFMPEG_BIN="/opt/homebrew/bin/ffmpeg"

/bin/test -f ${TMUX_BIN} || exit 33
/bin/test -f ${FFMPEG_BIN} || exit 34

${TMUX_BIN} kill-session -t "${TMUX_SESSION_NAME}" || true

${TMUX_BIN} new-session -d -s "${TMUX_SESSION_NAME}" \
bash -c "echo \$\$ > ${RECORDING_PID}; \
    exec nice -n 10 \
    ${FFMPEG_BIN} \
            -hide_banner \
            -loglevel info \
            -f mjpeg \
            -framerate 5 \
            -i \"${URL}\" \
            -vf 'pad=ceil(iw/2)*2:ceil(ih/2)*2' \
            -vf 'scale=400:-2' \
            -an \
            -threads 1 \
            -t \"00:15:00\" \
            -vcodec h264 \
                -preset ultrafast \
                -tune animation \
                -pix_fmt yuv420p \
            -metadata comment=\"${RECORDING}\" \
            -y \
            \"${RECORDING}\" \
            > \"${RECORDING_LOG}\" 2>&1"
