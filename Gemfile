source 'https://rubygems.org'

gemspec name: 'json'

group :development do
  gem "ruby_memcheck" if RUBY_PLATFORM =~ /linux/i
  gem "ostruct"
  gem "rake"
  gem "rake-compiler"
  gem "test-unit"
  gem "test-unit-ruby-core"
  gem "all_images", "~> 0" unless RUBY_PLATFORM =~ /java/

  if ENV['BENCHMARK']
    gem "benchmark-ips"
    unless RUBY_PLATFORM =~ /java/
      gem "oj"
      gem "rapidjson"
    end
  end
end
