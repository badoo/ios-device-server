module IosDeviceServerClient
  module Permissions
    module Type
      CALENDAR      = 'calendar'.freeze
      CAMERA        = 'camera'.freeze
      CONTACTS      = 'contacts'.freeze
      HOME_KIT      = 'homekit'.freeze
      MICROPHONE    = 'microphone'.freeze
      PHOTOS        = 'photos'.freeze
      REMINDERS     = 'reminders'.freeze
      MEDIA_LIBRARY = 'medialibrary'.freeze
      MOTION        = 'motion'.freeze
      HEALTH        = 'health'.freeze
      SIRI          = 'siri'.freeze
      SPEECH        = 'speech'.freeze
    end

    module Allowed
      YES    = 'yes'.freeze
      NO     = 'no'.freeze
      ALWAYS = 'always'.freeze
      INUSE  = 'inuse'.freeze
      NEVER  = 'never'.freeze
      UNSET  = 'unset'.freeze
    end
  end
end
