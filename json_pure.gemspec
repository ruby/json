# frozen_string_literal: true

version = File.foreach(File.join(__dir__, "lib/json/version.rb")) do |line|
  /^\s*VERSION\s*=\s*'(.*)'/ =~ line and break $1
end rescue nil

Gem::Specification.new do |s|
  s.name = "json_pure"
  s.version = version

  s.summary = "JSON Implementation for Ruby"
  s.description = "This is a JSON implementation in pure Ruby."
  s.licenses = ["Ruby"]
  s.authors = ["Florian Frank"]
  s.email = "flori@ping.de"

  s.extra_rdoc_files = ["README.md"]
  s.rdoc_options = ["--title", "JSON implementation for ruby", "--main", "README.md"]
  s.files = [
    "CHANGES.md",
    "COPYING",
    "BSDL",
    "LEGAL",
    "README.md",
    "json_pure.gemspec",
    "lib/json.rb",
    "lib/json/add/bigdecimal.rb",
    "lib/json/add/complex.rb",
    "lib/json/add/core.rb",
    "lib/json/add/date.rb",
    "lib/json/add/date_time.rb",
    "lib/json/add/exception.rb",
    "lib/json/add/ostruct.rb",
    "lib/json/add/range.rb",
    "lib/json/add/rational.rb",
    "lib/json/add/regexp.rb",
    "lib/json/add/set.rb",
    "lib/json/add/struct.rb",
    "lib/json/add/symbol.rb",
    "lib/json/add/time.rb",
    "lib/json/common.rb",
    "lib/json/ext.rb",
    "lib/json/generic_object.rb",
    "lib/json/pure.rb",
    "lib/json/pure/generator.rb",
    "lib/json/pure/parser.rb",
    "lib/json/version.rb",
  ]
  s.homepage = "https://ruby.github.io/json"
  s.metadata = {
    'bug_tracker_uri'   => 'https://github.com/ruby/json/issues',
    'changelog_uri'     => 'https://github.com/ruby/json/blob/master/CHANGES.md',
    'documentation_uri' => 'https://ruby.github.io/json/doc/index.html',
    'homepage_uri'      => s.homepage,
    'source_code_uri'   => 'https://github.com/ruby/json',
    'wiki_uri'          => 'https://github.com/ruby/json/wiki'
  }

  s.required_ruby_version = Gem::Requirement.new(">= 2.7")
end
