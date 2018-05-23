require 'base64'
require 'json'
require 'net/http'
require 'selenium-webdriver'
require 'uri'

require_relative 'device_info'

module IosDeviceServerClient
  class FbsimctlClientError < RuntimeError
  end

  class DeviceClient
    module ScreenshotType
      PNG = 1
      JPEG = 2
    end

    attr_reader :user_ports

    # @return [DeviceClient]
    def self.from_hash(device)
      fb_endpoint = device['fbsimctl_endpoint']
      wda_endpoint = device['wda_endpoint']
      user_ports = device['user_ports']
      DeviceClient.new(fb_endpoint, wda_endpoint, user_ports)
    end

    def initialize(fb_endpoint, wda_endpoint, user_ports)
      uri = fb_endpoint
      uri += '/' unless uri.end_with?('/')
      @client = FbHttpServerClient.new(uri)
      @wda_endpoint = wda_endpoint
      @user_ports = user_ports
    end

    def create_driver(caps)
      client = Selenium::WebDriver::Remote::Http::Default.new
      client.open_timeout = 15 # seconds
      client.read_timeout = 120 # seconds
      Selenium::WebDriver.for(:remote, url: @wda_endpoint, desired_capabilities: caps, http_client: client)
    end

    def screenshot(file_type = ScreenshotType::PNG)
      path = case file_type
             when ScreenshotType::PNG
               'screenshot.png'
             when ScreenshotType::JPEG
               'screenshot.jpeg'
             else
               raise(ArgumentError, "Unknown file type #{file_type}, should be one of #{ScreenshotType.constants}")
             end
      @client.get_binary(path)
    end

    def open_url(url)
      res = @client.post('open', url: url)
      raise_for_status(res)
    end

    def install_app(app_bundle_path)
      raise("App bundle #{app_bundle_path} is not a file. Please provide path to a zipped app bundle") unless File.file?(app_bundle_path)
      length = File.size(app_bundle_path)
      res = File.open(app_bundle_path, 'rb') do |stream|
        @client.post_binary('install', stream, length)
      end
      raise_for_status(res)
    end

    def list_apps
      res = @client.get('list_apps')
      raise_for_status(res)
      res
    end

    def app_installed?(bundle_id)
      rv = list_apps
      app = rv['events'][0]['subject'].find { |x| x['bundle']['bundle_id'] == bundle_id }
      # FIXME: check for data container does not work on real devices, do we still need it for sims to ensure app completed installation?
      #  && app['data_container']
      !app.nil?
    end

    def uninstall_app(bundle_id)
      res = @client.post('uninstall', bundle_id: bundle_id)
      raise_for_status(res)
    end

    def launch_app(bundle_id)
      res = @client.post('launch', bundle_id: bundle_id)
      raise_for_status(res)
    end

    def relaunch_app(bundle_id)
      res = @client.post('relaunch', bundle_id: bundle_id)
      raise_for_status(res)
    end

    def terminate_app(bundle_id)
      res = @client.post('terminate', bundle_id: bundle_id)
      raise_for_status(res)
    end

    def set_location(lat:, lon:)
      res = @client.post('set_location', latitude: lat, longitude: lon)
      raise_for_status(res)
    end

    private

    def raise_for_status(res)
      msg = JSON.pretty_unparse(res) rescue res # rubocop:disable Style/RescueModifier
      raise(FbsimctlClientError, "fbsimctl request failed #{msg}") unless res.is_a?(Hash) && res['status'] == 'success'
    end
  end

  class FbHttpServerClient
    def initialize(endpoint)
      @endpoint = endpoint
    end

    def get(path)
      uri = URI.join(@endpoint, path)
      res = Net::HTTP.get_response(uri)
      JSON.parse(res.body)
    end

    def get_binary(path)
      uri = URI.join(@endpoint, path)
      http = Net::HTTP.new(uri.host, uri.port)
      request = Net::HTTP::Get.new(uri.request_uri)
      res = http.request(request).response
      res.value
      res.body
    end

    def post(path, payload)
      data = JSON.dump(payload)
      uri = URI.join(@endpoint, path)
      http = Net::HTTP.new(uri.host, uri.port)
      request = Net::HTTP::Post.new(uri.request_uri)
      request.body = data
      res = http.request(request).response
      JSON.parse(res.body) unless res.body.empty?
    end

    def post_binary(path, stream, length, read_timeout: 60 * 2)
      uri = URI.join(@endpoint, path)
      http = Net::HTTP.new(uri.host, uri.port)
      http.read_timeout = read_timeout
      request = Net::HTTP::Post.new(uri.request_uri, 'Content-Length' => length.to_s)
      # request.body = binary
      request.body_stream = stream
      res = http.request(request).response
      JSON.parse(res.body)
    end
  end
end
