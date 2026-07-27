source 'https://rubygems.org'

gemspec

group :development do
  gem "ruby_memcheck" if RUBY_PLATFORM =~ /linux/i
  gem "bigdecimal"
  gem "rake"
  gem "rake-compiler"
  gem "test-unit"
  gem "test-unit-ruby-core"
  gem "simplecov", require: false

  if RUBY_VERSION < "3.1."
    # Ref: https://github.com/test-unit/test-unit/pull/329
    gem 'power_assert', '< 3.0.0'
  end
end

group :release do
  gem "net-http"
  gem "uri"
end

if ENV['BENCHMARK']
  gem "benchmark-ips"
  unless RUBY_PLATFORM =~ /java/
    gem "oj"
    gem "rapidjson"
  end
end
