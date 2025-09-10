$LOAD_PATH.unshift(File.expand_path('../../../ext', __FILE__), File.expand_path('../../../lib', __FILE__))

require 'test/unit'

if ENV["JSON_COMPACT"]
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
end

unless defined?(Test::Unit::CoreAssertions)
  require "core_assertions"
  Test::Unit::TestCase.include Test::Unit::CoreAssertions
end

# The built-in Coverage module (and therefore SimpleCov) need to be activated prior to any require statements.
# But! SimpleCov requires 'json' before activating Coverage measurement, meaning it misses several files.
#
# The solution is to defer any JSON requires from SimpleCov, which works out because we require it ourselves before
# SimpleCov actually uses it for anything.
module JSONTestPatch
  def require(name)
    if name == 'json'
      caller_path = caller_locations.first.path

      return false if caller_path.match? %r(/simplecov/)
    end

    super(name)
  end
end
Kernel.prepend JSONTestPatch

begin
  require 'simplecov'
rescue LoadError
  # Don't fail Ruby's test suite
else
  # Override default at_exit or else it will fire when the Rake task process ends early
  SimpleCov.external_at_exit = true
  Test::Unit.at_exit do
    SimpleCov.at_exit_behavior
  end

  SimpleCov.start do
    # Force SimpleCov to include all files in its report, avoiding accidental require-order misses
    track_files 'lib/**/*.rb'

    add_filter 'lib/json/truffle_ruby' unless RUBY_ENGINE == 'truffleruby'

    enable_coverage :branch
    primary_coverage :branch
  end
end

# Require must be after SimpleCov is started for it to see the code
require 'json'
