require 'base64'
require 'json'
require 'net/http'
require 'uri'

require_relative 'device_client'

module IosDeviceServerClient
  class DeviceProviderFactory
    # @return [DeviceProvider]
    def self.create(server_url, read_timeout: 120)
      DeviceProvider.new(server_url, read_timeout: read_timeout, credentials: DeviceServerCredentials.from_env)
    end
  end

  class DeviceServerCredentials
    attr_reader :token

    def initialize(token)
      if token.nil? || token.strip.empty?
        raise(ArgumentError, 'token cannot be nil or empty')
      end
      @token = token
    end

    def self.from_env
      token = ENV.fetch('DEVICE_SERVER_AUTH_TOKEN', '').strip
      token.empty? ? nil : DeviceServerCredentials.new(token)
    end

    def to_s
      "<#{self.class}: #{@token}>"
    end
  end

  # @server in RemoteDevice
  class DeviceProvider
    attr_reader :credentials

    # @param [String] endpoint
    # @param [Fixnum] read_timeout
    # @param [DeviceServerCredentials] credentials
    def initialize(endpoint, read_timeout: 120, credentials: nil)
      if endpoint.nil? || endpoint.empty?
        raise(ArgumentError, 'endpoint cannot be nil or empty')
      end

      @endpoint = URI(endpoint)
      @read_timeout = read_timeout
      @credentials = credentials
    end

    def server_url
      @endpoint
    end

    def capacity(dc)
      with_http do |http|
        http.post('/devices/-/capacity', JSON.dump(dc))
      end
    end

    def create(dc)
      with_http do |http|
        headers = { 'Content-Type' => 'application/json' }

        if credentials
          headers['Authorization'] = auth_header_value
        end

        request = Net::HTTP::Post.new('/devices', headers)
        request.body = JSON.dump(dc)
        http.request(request)
      end
    end

    def get(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.get("/devices/#{device_ref}")
      end
    end

    def endpoint_for(device_ref, port)
      raise_if_ref_is_empty(device_ref)

      res = with_http do |http|
        http.get("/devices/#{device_ref}/endpoint/#{port}")
      end
      res['endpoint']
    end

    def last_crash_log(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.get("/devices/#{device_ref}/crashes/last")
      end
    end

    def reset(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.post("/devices/#{device_ref}", JSON.dump(action: 'reset'))
      end
    end

    def clear_safari_cookies(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.post("/devices/#{device_ref}", JSON.dump(action: 'clear_safari_cookies'))
      end
    end

    def approve_access(device_ref, bundle_ids)
      raise_if_ref_is_empty(device_ref)

      payload = bundle_ids.map { |x| { bundle_id: x } }
      with_http do |http|
        http.post("/devices/#{device_ref}/permissions", JSON.dump(payload))
      end
    end

    def video_start(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.post("/devices/#{device_ref}/video", JSON.dump(start: true))
      end
    end

    def video_stop(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.post("/devices/#{device_ref}/video", JSON.dump(start: false))
      end
    end

    def video_get(device_ref)
      raise_if_ref_is_empty(device_ref)

      Net::HTTP.start(@endpoint.host, @endpoint.port) do |http|
        res = http.get("/devices/#{device_ref}/video")
        raise_for_status(res)
        res.body
      end
    end

    def state(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.get("/devices/#{device_ref}/state")
      end
    end

    def release(device_ref)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        http.delete("/devices/#{device_ref}")
      end
    end

    def release_user_devices
      with_http do |http|
        request = Net::HTTP::Delete.new('/devices/')
        request['Authorization'] = auth_header_value
        http.request(request)
      end
    end

    def run_xcuitest(device_ref, test_execution_config)
      raise_if_ref_is_empty(device_ref)

      with_http do |http|
        headers = { 'Content-Type' => 'application/json' }
 
        if credentials
          headers['Authorization'] = auth_header_value
        end

        request = Net::HTTP::Post.new("/devices/#{device_ref}/run_xcuitest", headers)
        request.body = JSON.dump(test_execution_config)
        return http.request(request)
      end
    end

    # @note internal use only
    def list
      with_http do |http|
        http.get('/devices')
      end
    end

    private

    def auth_header_value
      if @credentials.nil?
        raise(ArgumentError, 'Can not set auth header if on credentials were provided')
      end

      "Bearer #{Base64.strict_encode64(@credentials.token)}"
    end

    def raise_if_ref_is_empty(device_ref)
      raise(ArgumentError, 'device_ref cannot be nil or empty') if device_ref.nil? || device_ref.strip.empty?
    end

    def with_http(&_block)
      res = Net::HTTP.start(@endpoint.host, @endpoint.port) do |http|
        http.open_timeout = 5
        http.read_timeout = @read_timeout
        yield(http)
      end
      raise_for_status(res)
      JSON.parse(res.body)
    rescue Errno::ECONNREFUSED => ex
      raise(DeviceProviderError, "Device Server can not be reached. Please ensure Device Server is started. #{ex}")
    end

    def raise_for_status(res)
      return if res.is_a? Net::HTTPSuccess

      msg = JSON.pretty_unparse(JSON.parse(res.body)) rescue res.body # rubocop:disable Style/RescueModifier

      if res.code == '429'
        raise(DeviceProviderCapacityError, "Device Server Capacity Error #{res.code}: #{msg}")
      else
        raise(DeviceProviderError, "Device Server Error #{res.code}: #{msg}")
      end
    end
  end

  class DeviceProviderError < RuntimeError
  end

  class DeviceProviderCapacityError < DeviceProviderError
  end
end
