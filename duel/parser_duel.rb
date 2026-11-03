# frozen_string_literal: true

require_relative "duel_helper"

# Parsing, through JSON.parse, as most callers do: the documents from
# benchmark/parser.rb, the options that change the parser's hot loop,
# small payloads where the per-call overhead counts, and the string and
# number paths on their own.

duel "parse: twitter.json" do
  once { @source = fixture("twitter.json") }
  JSON.parse(@source)
end

duel "parse: twitter.json with symbolize_names" do
  once { @source = fixture("twitter.json") }
  JSON.parse(@source, symbolize_names: true)
end

duel "parse: twitter.json with freeze" do
  once { @source = fixture("twitter.json") }
  JSON.parse(@source, freeze: true)
end

duel "parse: citm_catalog.json" do
  once { @source = fixture("citm_catalog.json") }
  JSON.parse(@source)
end

duel "parse: canada.json (floats)" do
  once { @source = fixture("canada.json") }
  JSON.parse(@source)
end

duel "parse: activitypub.json" do
  once { @source = fixture("activitypub.json") }
  JSON.parse(@source)
end

duel "parse: a small hash" do
  once { @source = '{"username":"jhawthorn","id":123,"event":"wrote json serializer"}' }
  JSON.parse(@source)
end

duel "parse: a 1 KB API response" do
  JSON.parse(API_RESPONSE)
end

duel "parse: a 1 KB API response with symbolize_names" do
  JSON.parse(API_RESPONSE, symbolize_names: true)
end

duel "parse: a 1 KB API response with JSON::Coder" do
  once { @coder = JSON::Coder.new }
  @coder.load(API_RESPONSE)
end

duel "parse: the nested document from oj's tests" do
  once do
    @source = <<~'JSON'
      {"a":"Alpha","b":true,"c":12345,"d":[true,[false,[-123456789,null],3.9676,["Something else.",false],null]],
      "e":{"zero":null,"one":1,"two":2,"three":[3],"four":[0,1,2,3,4]},"f":null,
      "h":{"a":{"b":{"c":{"d":{"e":{"f":{"g":null}}}}}}},"i":[[[[[[[null]]]]]]]}
    JSON
  end
  JSON.parse(@source)
end

duel "parse: 1,000 integers" do
  once { @source = "[#{(1_000_000..1_000_999).to_a.join(',')}]" }
  JSON.parse(@source)
end

duel "parse: 1,000 short decimals" do
  once { @source = "[#{short_decimals(1000).join(',')}]" }
  JSON.parse(@source)
end

duel "parse: 100 strings with escapes" do
  once do
    @source = "[#{Array.new(100) { |i| %("Line #{i}: \\"quoted\\"\\n\\tC:\\\\Users\\\\octocat \\u0001 caf\\u00e9 \\ud83d\\ude00") }.join(',')}]"
  end
  JSON.parse(@source)
end

duel "parse: 100 ASCII strings of 1 KB" do
  once { @source = "[#{Array.new(100) { |i| %("#{i} #{'The quick brown fox jumps over the lazy dog. ' * 22}") }.join(',')}]" }
  JSON.parse(@source)
end

# A stream of documents, one per line, fed to a ResumableParser in 4 KB
# chunks of bytes, as a socket reads them.
duel "parse: a stream of 50 API responses with ResumableParser" do
  once do
    stream = "#{API_RESPONSE.delete("\n")}\n" * 50
    @chunks = stream.b.scan(/.{1,4096}/mn)
  end
  parser = JSON::ResumableParser.new
  @chunks.each do |chunk|
    parser << chunk
    parser.value while parser.parse
  end
end
