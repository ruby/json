$LOAD_PATH.unshift(File.expand_path('../../../ext', __FILE__), File.expand_path('../../../lib', __FILE__))

require "coverage"
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

Test::Unit.at_exit do
  begin
    require 'simplecov'
  rescue LoadError
    # Don't fail Ruby's test suite
  else
    # Force SimpleCov to include all files in its report, avoiding accidental require-order misses
    SimpleCov.track_files 'lib/**/*.rb'

    SimpleCov.add_filter 'lib/json/truffle_ruby' unless RUBY_ENGINE == 'truffleruby'

    SimpleCov.enable_coverage :branch
    SimpleCov.primary_coverage :branch

    # must be true for SimpleCov to generate a result at all
    SimpleCov.running = true
    coverage = SimpleCov.result

    SimpleCov.write_last_run(coverage)
    coverage.format!
  end
end

# Start built-in Coverage directly because SimpleCov depends on JSON gem, wrecking the require order.
# SimpleCov is still used for the pretty formatting afterward.
# require is at end of file to avoid coverage monitoring any irrelevant code (eg. Test::Unit)
Coverage.start(lines: true, branches: true)
require 'json'
