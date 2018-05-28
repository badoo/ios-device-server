# iOS Remote Device Server [![Build Status](https://travis-ci.org/badoo/ios-device-server.svg?branch=master)](https://travis-ci.org/badoo/ios-device-server)

A server for managing, booting, and controlling simulators and devices on remote host machines.

## Features
- Enables control of simulators and devices connected to remote host machines
- Enables tests to run using remote simulators and devices
- Enables custom actions on simulators like clearing safari cookies or fast reset of a simulator
- Hides away satisfying desired capabilities, i.e. based on requested model or os will choose an appropriate host to create a simulator

## Requirements
### Java
* [Download](http://google.com/#q=download+java+se) and install Java SDK 10
* set environment variable JAVA_HOME
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 10 -F)
```

## Usage

Build and run Device Server
```bash
./bootstrap.sh
cd device-server
./run_device_server.sh
``` 

Allocate Device
```bash
curl -X POST -d '{"model":"iPhone 6", "headless":false}' http://localhost:4567/devices
```

Query Device Server
```bash
curl http://localhost:4567/status
curl http://localhost:4567/devices
```

Release device by reference
```bash
curl -X DELETE http://localhost:4567/devices/${DEVICE_REF}
```

Ruby sample
```ruby
require 'ios-device-server-client/remote_device'

server_url = 'http://localhost:4567'

provider = IosDeviceServerClient::DeviceProvider.new(server_url)

rv = provider.create(model: 'iPhone 6', os: 'iOS 11.0', headless: false)
remote_device = IosDeviceServerClient::RemoteDevice.new(server_url, rv['ref'])

begin
  remote_device.await(timeout: 30)
  remote_device.open_url('https://github.com/badoo/ios-device-server')
  readline
ensure
  remote_device.release
end
```


- [ ] command line utility will be published soon
