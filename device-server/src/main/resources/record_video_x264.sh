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

set -xe

nohup \
    nice -n 10 \
    env PATH="${PATH}" \
        ffmpeg \
            -hide_banner \
            -loglevel info \
            -f mjpeg \
            -framerate 5 \
            -i "${URL}" \
            -vf 'pad=ceil(iw/2)*2:ceil(ih/2)*2' \
            -vf 'scale=400:-2' \
            -an \
            -threads 1 \
            -t "00:15:00" \
            -vcodec h264 \
                -preset ultrafast \
                -tune animation \
                -pix_fmt yuv420p \
            -metadata comment="${RECORDING}" \
            -y \
            "${RECORDING}" \
            &> "${RECORDING_LOG}" 2>&1 &
echo $! > "${RECORDING_PID}"
