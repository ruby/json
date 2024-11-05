# frozen_string_literal: true


unless defined?(::JSON::JSON_LOADED)
  module JSON
    # Prevent json/ext from being loaded at the same time
    $LOADED_FEATURES << File.expand_path("../ext.rb", __FILE__)

    # We use require_relative to ensure we're not loading files from `json`
    require_relative 'common'
    require_relative 'pure/parser'
    require_relative 'pure/generator'

    $DEBUG and warn "Using Pure library for JSON."
    JSON.parser = JSON::Pure::Parser
    JSON.generator = JSON::Pure::Generator

    JSON_LOADED = true
  end
end
