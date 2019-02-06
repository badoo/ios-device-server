require 'uri'
require 'base64'

require_relative 'device_provider'
require_relative 'device_client'
require_relative 'permissions'

module IosDeviceServerClient
  module RemoteDeviceError
    class ConnectionError < Errno::ECONNREFUSED
      def initialize(device_ref, state, cause)
        super("WebDriver connection refused for #{device_ref} in #{state}. Original error: #{cause}")
      end
    end

    class NotSupportedError < RuntimeError
      def initialize(device_ref, capability)
        super("Device #{device_ref} does not support #{capability}")
      end
    end
  end

  class RemoteDevice
    attr_reader :info

    # @return [String]
    attr_reader :wda_endpoint

    # @return [Fixnum]
    attr_reader :calabash_port

    # @return [String]
    attr_reader :device_ref

    def initialize(server_endpoint, device_ref)
      raise(ArgumentError, 'device_ref cannot be null') if device_ref.nil?

      @server = DeviceProviderFactory.create(server_endpoint)
      @device_ref = device_ref
      @device = nil # fbsimctl client
      device = @server.get(@device_ref)
      @actual_capabilities = device.fetch('capabilities', {})
      info = device['info']
      @wda_endpoint = device['wda_endpoint']
      @calabash_port = device['calabash_port']
      @info = DeviceInfo.new(udid: info['udid'], model: info['model'], name: info['name'], os: info['os'], arch: info['arch'])
    end

    def to_s
      "<RemoteDevice:#{@device_ref} | #{@info} | calabash_port: #{@calabash_port} | wda_endpoint: #{@wda_endpoint}>"
    end

    # region: device management

    def release
      @server.release(@device_ref)
    end

    def reset(timeout: 60)
      ensure_ready
      @device = nil
      @server.reset(@device_ref)
      await(timeout: timeout)
    end

    def await(timeout: 60)
      last_state = nil
      rv = wait_for(timeout: timeout, retry_frequency: 1.5) do
        res = @server.state(@device_ref)
        # short-circuit awaiting if device entered failed state (unrecoverable)
        raise("Awaited device #{@device_ref} failed with #{res}") if res['state'] == 'failed'
        last_state = res
        res['ready']
      end
      raise("Timed out waiting #{@device_ref} to be ready after #{timeout} s. Last state is #{last_state}") unless rv
      @device = DeviceClient.from_hash(@server.get(@device_ref))
    end

    def set_location(lat:, lon:)
      ensure_ready
      if @actual_capabilities.fetch('set_location', true)
        @device.set_location(lat: lat, lon: lon)
      else
        raise(RemoteDeviceError::NotSupportedError.new(@device_ref, __method__))
      end
    end

    def clear_safari_cookies
      ensure_ready
      @server.clear_safari_cookies(@device_ref)
    end

    # endregion

    # region: app management
    def approve_access(bundle_ids)
      ensure_ready
      @server.approve_access(@device_ref, bundle_ids)
    end

    # @param [String] bundle_id
    # @param [Hash<String, String>] permissions map {Permissions::Type} to {Permissions::Allowed}
    def set_permissions(bundle_id:, permissions:)
      ensure_ready
      @server.set_permissions(@device_ref, bundle_id: bundle_id, permissions: permissions)
    end

    def app_installed?(bundle_id)
      ensure_ready
      @device.app_installed?(bundle_id)
    end

    def install_app(app_bundle_path)
      ensure_ready
      @device.install_app(app_bundle_path)
    end

    def launch_app(bundle_id)
      ensure_ready
      @device.launch_app(bundle_id)
    end

    def list_apps
      ensure_ready
      @device.list_apps
    end

    def open_url(url)
      ensure_ready
      @device.open_url(url)
    end

    def relaunch_app(bundle_id)
      ensure_ready
      @device.relaunch_app(bundle_id)
    end

    def terminate_app(bundle_id)
      ensure_ready
      if @actual_capabilities.fetch('terminate_app', true)
        @device.terminate_app(bundle_id)
      else
        raise(RemoteDeviceError::NotSupportedError.new(@device_ref, __method__))
      end
    end

    def uninstall_app(bundle_id)
      ensure_ready
      @device.uninstall_app(bundle_id)
    end

    # endregion

    # region: web driver, calabash, network

    def user_ports
      # ports are known right after device creation, no need to await
      if @device.nil?
        device = @server.get(@device_ref)
        return device['user_ports']
      end
      @device.user_ports
    end

    def create_driver(caps)
      ensure_ready
      @device.create_driver(caps)
    rescue Errno::ECONNREFUSED => e
      state = @server.state(@device_ref) rescue nil # rubocop:disable Style/RescueModifier
      raise(RemoteDeviceError::ConnectionError.new(@device_ref, state, e))
    end

    def endpoint_for(port)
      ensure_ready
      @server.endpoint_for(@device_ref, port)
    end

    # endregion

    # region: diagnostics

    def screenshot(filetype = DeviceClient::ScreenshotType::PNG, prefer_wda: true)
      if prefer_wda
        begin
          return wda_screenshot
        rescue RuntimeError => ex
          puts "Error trying to retrieve screenshot using WDA #{ex}. Will fallback to fbsimctl"
        end
      end

      ensure_ready
      @device.screenshot(filetype)
    end

    def video_acquire
      @server.video_get(@device_ref)
    end

    def video_start
      @server.video_start(@device_ref)
    end

    def video_stop
      @server.video_stop(@device_ref)
    end

    def last_crash_log
      ensure_ready
      @server.last_crash_log(@device_ref)
    end

    # endregion

    private

    def wda_screenshot
      uri = URI.join(@wda_endpoint, 'screenshot')
      res = Net::HTTP.start(uri.host, uri.port) do |http|
        http.get(uri.request_uri)
      end

      raise("WDA Screenshot request failed #{res.code}: #{res.body}") unless res.is_a? Net::HTTPSuccess
      data = JSON.parse(res.body)['value']
      rv = Base64.decode64(data)
      raise('WDA Screenshot returned empty data') if rv.empty?
      rv
    rescue Errno::ECONNREFUSED => ex
      raise("WDA Screenshot request failed to connect. #{ex}")
    end

    def ensure_ready
      raise("#{@device_ref} is not ready. Have you forgot to #await device?") if @device.nil?
    end

    def wait_for(timeout:, retry_frequency:, &_block)
      finish = timeout && Time.now + timeout
      begin
        result = yield
        break if result
        sleep(retry_frequency)
        now = Time.now
      end until (finish && finish < now)
      result
    end
  end
end
