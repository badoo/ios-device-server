#!/bin/bash

set -xe

#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER=/Users/vfrolov/bma-calabash/chappy/infrastructure/ios-device-server/ios/facebook/simulators-Facebook-c233bf07-x10/WebDriverAgentRunner-Runner.app
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER=/Users/vfrolov/bma-calabash/chappy/infrastructure/ios/facebook/simulators/WebDriverAgentRunner-Runner.app # Appium-d50d01d9-x10
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER=/Users/vfrolov/bma-calabash/chappy/infrastructure/ios-device-server/ios/facebook/simulators-Appium-d50d01d9-x10/WebDriverAgentRunner-Runner.app
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER=/Users/vfrolov/bma-calabash/chappy/infrastructure/ios-device-server/ios/facebook/simulators-Facebook-c233bf07-x10/WebDriverAgentRunner-Runner.app
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER='/Users/vfrolov/bma-calabash/chappy/infrastructure/ios/facebook/simulators/WebDriverAgentRunner-Runner.app'

# свежак
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER='/Users/vfrolov/bma-calabash/chappy/infrastructure/ios-device-server/ios/facebook/simulators-Appium-7fc54afc-x10/WebDriverAgentRunner-Runner.app'
export DEVICE_SERVER_WDA_SIMULATOR_RUNNER='/Users/vfrolov/bma-calabash/chappy/infrastructure/ios-device-server/ios/facebook/simulators-Facebook-c233bf07-x10/WebDriverAgentRunner-Runner.app'
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER='/Users/vfrolov/bma-calabash/chappy/infrastructure/ios-device-server/ios/facebook/simulators-Facebook-c233bf07-x10/WebDriverAgentRunner-Runner.app'
# дефолтовое старьё на 11.4
#export DEVICE_SERVER_WDA_SIMULATOR_RUNNER='/Users/vfrolov/bma-calabash/badoo/infrastructure/ios/facebook/simulators/WebDriverAgentRunner-Runner.app'

#export DEVICE_SERVER_FBSIMCTL_VERSION='HEAD-33840ba4-x10'
export DEVICE_SERVER_FBSIMCTL_VERSION='HEAD-b121dad-x9'
export DEVICE_SERVER_WDA_SIMULATOR_RUNNER='/Users/vfrolov/bma-calabash/infrastructure/ios/facebook/simulators/WebDriverAgentRunner-Runner.app'
export DEVICE_SERVER_CONFIG_PATH='/Users/vfrolov/device-server.config.json'
./run_device_server.sh


