# frozen_string_literal: true

require 'json/common'

module JSON
  # This module holds all the modules/classes that implement JSON's
  # functionality as C extensions.
  module Ext
    class Parser
      class << self
        def parse(...)
          new(...).parse
        end
        alias_method :parse, :parse # Allow redefinition by extensions
      end

      def initialize(source, opts = nil)
        @source = source
        @config = Config.new(opts)
      end

      def source
        @source.dup
      end

      def parse
        @config.parse(@source)
      end
    end

    ruby_version = /(\d+\.\d+)/.match(RUBY_VERSION)

    begin
      require "json/ext/#{ruby_version}/parser"
    rescue LoadError
      require "json/ext/parser"
    end

    Ext::Parser::Config = Ext::ParserConfig
    JSON.parser = Ext::Parser

    if RUBY_ENGINE == 'truffleruby'
      require 'json/truffle_ruby/generator'
      JSON.generator = JSON::TruffleRuby::Generator
    else
      begin
        require "json/ext/#{ruby_version}/generator"
      rescue LoadError
        require "json/ext/generator"
      end
      JSON.generator = Generator
    end
  end

  JSON_LOADED = true unless defined?(JSON::JSON_LOADED)
end
