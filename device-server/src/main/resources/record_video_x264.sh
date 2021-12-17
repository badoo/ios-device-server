#!/bin/zsh

# /opt/homebrew/bin — Homebrew PATH on Macs with Apple silicon
# /usr/local/bin — Homebrew PATH on Macs with Intel CPU

PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:${PATH}"

readonly UDID=${1}
readonly URL=${2}
readonly RECORDING="${TMPDIR}videoRecording_${UDID}.mp4"

set -x

nohup \
    nice -n 10 \
        ffmpeg \
            -hide_banner \
            -loglevel info \
            -f mjpeg \
            -framerate 4 \
            -i "${URL}" \
            -vf 'pad=ceil(iw/2)*2:ceil(ih/2)*2' \
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
            &> "${RECORDING}.log" 2>&1 &
