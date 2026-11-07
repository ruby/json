# frozen_string_literal: true

# The documents in benchmark/data, the same ones that benchmark/parser.rb
# and benchmark/encoder.rb compare against other parsers with.
def fixture(name)
  File.read(File.expand_path("../benchmark/data/#{name}", __dir__), encoding: Encoding::UTF_8)
end

# The first +count+ points of canada.json's polygon, as a document of its
# own, with the numbers exactly as the file writes them (most with 17
# significant digits). The whole file takes several milliseconds to
# generate, too long for one iteration.
def canada_points(count)
  points = fixture("canada.json").scan(/\[-?\d+(?:\.\d+)?,-?\d+(?:\.\d+)?\]/).first(count)
  %({"type":"Polygon","coordinates":[[#{points.join(',')}]]})
end

# A typical response from a JSON API, about 1 KB: a record with a few
# nested records, short and escaped strings, integers, floats, booleans
# and a null. Written out, so every version parses the same bytes.
API_RESPONSE = <<~'JSON'
  {"id":1234567,"login":"octocat","name":"The Octocat","email":null,"site_admin":false,
  "score":98.6,"created_at":"2026-01-21T08:41:44.286Z","tags":["ruby","json","api"],
  "plan":{"name":"pro","space":976562499,"private_repos":9999,"collaborators":0},
  "repos":[
  {"id":1000,"name":"repo-0","fork":false,"stars":0,"price":19.99,"description":"A \"quoted\" description.\nLine two."},
  {"id":1001,"name":"repo-1","fork":true,"stars":42,"price":4.5,"description":"Caf\u00e9 au lait, served in cafés."},
  {"id":1002,"name":"repo-2","fork":false,"stars":84,"price":0.99,"description":"Tabs\tand slashes: C:\\Users\\octocat"},
  {"id":1003,"name":"repo-3","fork":true,"stars":126,"price":120.0,"description":"Plain ASCII text, nothing to escape here."},
  {"id":1004,"name":"repo-4","fork":false,"stars":168,"price":1e-3,"description":"Emoji: \ud83d\ude00 and ☃"}
  ]}
JSON

# The small payloads that benchmark/parser.rb and benchmark/encoder.rb use.
SMALL_HASH = { "username" => "jhawthorn", "id" => 123, "event" => "wrote json serializer" }.freeze
SMALL_MIXED = [1, "string", { a: 1, b: 2 }, [3, 4, 5]].freeze

# Prices and the like: +count+ decimals with up to two places, the same
# every time.
def short_decimals(count)
  random = Random.new(42)
  Array.new(count) { random.rand(1..100_000) / 100.0 }
end

# Strings as they come from users: quotes, newlines, tabs, backslashes,
# a control character, and non-ASCII text, which a generator escapes.
def strings_to_escape(count)
  Array.new(count) do |i|
    "Line #{i}: \"quoted\"\n\tC:\\Users\\octocat \u0001 caf\u00e9 \u{1F600}"
  end
end

# A value that generates by calling its own #to_json.
class Event
  def initialize(id, name)
    @id = id
    @name = name
  end

  def to_json(*args)
    { "id" => @id, "name" => @name }.to_json(*args)
  end
end

# A value without #to_json of its own, which generates as its #to_s.
class Tag
  def to_s
    "tag"
  end
end
