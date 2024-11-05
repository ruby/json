# frozen_string_literal: true

unless defined?(::JSON::JSON_LOADED)
  module JSON
    # Prevent json/pure from being loaded at the same time
    $LOADED_FEATURES << File.expand_path("../pure.rb", __FILE__)

    # We use require_relative to ensure we're not loading files from `json_pure`
    require_relative 'common'

    if RUBY_ENGINE == 'truffleruby'
      require 'json/ext/parser'
      # We use require_relative to make sure we're loading the same version.
      # Otherwise if the Gemfile include conflicting versions of `json` and `json_pure`
      # it may break.
      require_relative 'pure/generator'
      $DEBUG and warn "Using Ext extension for JSON parser and Pure library for JSON generator."
      JSON.parser = JSON::Ext::Parser
      JSON.generator = JSON::Pure::Generator
    else
      require 'json/ext/parser'
      require 'json/ext/generator'
      $DEBUG and warn "Using Ext extension for JSON."
      JSON.parser = JSON::Ext::Parser
      JSON.generator = JSON::Ext::Generator
    end

    JSON_LOADED = true
  end
end
