# frozen_string_literal: true

case ENV['JSON']
when 'pure'
  $LOAD_PATH.unshift(File.expand_path('../../../lib', __FILE__))
  require 'json/pure'
  begin
    require 'json/ext'
  rescue LoadError
    # Ensure `json/ext` can't be loaded after `json/pure`
  end
  if [JSON.generator, JSON.parser].map(&:name) != ["JSON::Pure::Generator", "JSON::Pure::Parser"]
    abort "Expected JSON::Pure to be loaded, got: #{[JSON.generator, JSON.parser]}"
  end
when 'ext'
  $LOAD_PATH.unshift(File.expand_path('../../../ext', __FILE__), File.expand_path('../../../lib', __FILE__))
  require 'json/ext'
  require 'json/pure'

  expected = if RUBY_ENGINE == 'truffleruby'
    ["JSON::Pure::Generator", "JSON::Ext::Parser"]
  else
    ["JSON::Ext::Generator", "JSON::Ext::Parser"]
  end

  if [JSON.generator, JSON.parser].map(&:name) != expected
    abort "Expected JSON::Ext to be loaded, got: #{[JSON.generator, JSON.parser]}"
  end
else
  $LOAD_PATH.unshift(File.expand_path('../../../ext', __FILE__), File.expand_path('../../../lib', __FILE__))
  require 'json'
end

$stderr.puts("Testing #{JSON.generator} and #{JSON.parser}")

require 'test/unit'

if GC.respond_to?(:verify_compaction_references)
  # This method was added in Ruby 3.0.0. Calling it this way asks the GC to
  # move objects around, helping to find object movement bugs.
  begin
    GC.verify_compaction_references(expand_heap: true, toward: :empty)
  rescue NotImplementedError, ArgumentError
    # Some platforms don't support compaction
  end
end

if GC.respond_to?(:auto_compact=)
  begin
    GC.auto_compact = true
  rescue NotImplementedError
    # Some platforms don't support compaction
  end
end

unless defined?(Test::Unit::CoreAssertions)
  require "core_assertions"
  Test::Unit::TestCase.include Test::Unit::CoreAssertions
end
