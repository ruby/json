# frozen_string_literal: true

require "date"
require "stringio"
require_relative "duel_helper"

# Generating, through JSON.generate and the other ways in: the documents
# from benchmark/encoder.rb, pretty and not, small payloads where the
# per-call overhead counts, and the string escaping and number
# formatting paths on their own, with the options that change them.

duel "generate: twitter.json" do
  once { @object = JSON.parse(fixture("twitter.json")) }
  JSON.generate(@object)
end

duel "generate: twitter.json pretty" do
  once { @object = JSON.parse(fixture("twitter.json")) }
  JSON.pretty_generate(@object)
end

duel "generate: citm_catalog.json" do
  once { @object = JSON.parse(fixture("citm_catalog.json")) }
  JSON.generate(@object)
end

duel "generate: activitypub.json" do
  once { @object = JSON.parse(fixture("activitypub.json")) }
  JSON.generate(@object)
end

duel "generate: 10,000 points from canada.json (floats)" do
  once { @object = JSON.parse(canada_points(10_000)) }
  JSON.generate(@object)
end

duel "generate: a small hash" do
  JSON.generate(SMALL_HASH)
end

duel "generate: a small array with symbol keys" do
  JSON.generate(SMALL_MIXED)
end

duel "generate: a 1 KB API response" do
  once { @object = JSON.parse(API_RESPONSE) }
  JSON.generate(@object)
end

duel "generate: a 1 KB API response pretty" do
  once { @object = JSON.parse(API_RESPONSE) }
  JSON.pretty_generate(@object)
end

duel "generate: a 1 KB API response with Hash#to_json" do
  once { @object = JSON.parse(API_RESPONSE) }
  @object.to_json
end

duel "generate: a 1 KB API response with JSON::Coder" do
  once do
    @object = JSON.parse(API_RESPONSE)
    @coder = JSON::Coder.new
  end
  @coder.dump(@object)
end

duel "generate: a 1 KB API response into an IO with JSON.dump" do
  once do
    @object = JSON.parse(API_RESPONSE)
    @io = StringIO.new(+"")
  end
  @io.truncate(0)
  @io.rewind
  JSON.dump(@object, @io)
end

duel "generate: 1,000 integers" do
  once { @object = (1_000_000..1_000_999).to_a }
  JSON.generate(@object)
end

duel "generate: 1,000 short decimals" do
  once { @object = short_decimals(1000) }
  JSON.generate(@object)
end

duel "generate: 100 strings with escapes" do
  once { @object = strings_to_escape(100) }
  JSON.generate(@object)
end

duel "generate: 10 strings of 4 KB, ASCII but for one character" do
  once { @object = Array.new(10) { ("a" * 2000) + "€" + ("a" * 2000) } }
  JSON.generate(@object)
end

duel "generate: 10 strings of 4 KB, all multibyte" do
  once { @object = Array.new(10) { "€" * 1333 } }
  JSON.generate(@object)
end

duel "generate: 100 strings with script_safe" do
  once { @object = Array.new(100) { |i| "<script>alert(#{i})</script> \u2028 https://example.com/a/b" } }
  JSON.generate(@object, script_safe: true)
end

duel "generate: 100 strings with ascii_only" do
  once { @object = strings_to_escape(100) }
  JSON.generate(@object, ascii_only: true)
end

# Objects that aren't JSON types: with a #to_json of their own, or
# generated as their #to_s (Time, Date, a class), as benchmark/encoder.rb's
# "many #to_json calls" does, with fixed values.
duel "generate: objects through #to_json and #to_s" do
  once do
    @object = Array.new(20) do |i|
      { event: Event.new(i, "signup"), tag: Tag.new, int: 12, float: 54.3, class: Float,
        time: Time.utc(2026, 1, 21, 8, 41, 44), date: Date.new(2026, 1, 21) }
    end
  end
  JSON.generate(@object)
end
