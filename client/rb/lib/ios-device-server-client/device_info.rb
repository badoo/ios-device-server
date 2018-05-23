module IosDeviceServerClient
  class DeviceInfo
    attr_reader :udid
    attr_reader :model
    attr_reader :name
    attr_reader :os
    attr_reader :arch

    def initialize(udid:, model:, name:, os:, arch:)
      @udid = udid
      @model = model
      @name = name
      @os = os
      @arch = arch
    end

    def to_s
      "#{udid} | #{@name} | #{@model} | #{os} | #{arch}"
    end
  end
end
