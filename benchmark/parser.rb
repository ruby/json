require "benchmark/ips"
require "json"
begin
  require "oj"
rescue LoadError
end

begin
  require "rapidjson"
rescue LoadError
end

if ENV["ONLY"]
  RUN = ENV["ONLY"].split(/[,: ]/).map{|x| [x.to_sym, true] }.to_h
  RUN.default = false
elsif ENV["EXCEPT"]
  RUN = ENV["EXCEPT"].split(/[,: ]/).map{|x| [x.to_sym, false] }.to_h
  RUN.default = true
else
  RUN = Hash.new(true)
end

def benchmark_parsing(name, json_output)
  puts "== Parsing #{name} (#{json_output.size} bytes)"
  coder = JSON::Coder.new

  Benchmark.ips do |x|
    x.report("json")      { JSON.parse(json_output) } if RUN[:json]
    x.report("json_coder") { coder.load(json_output) } if RUN[:json_coder]

    if defined?(Oj)
      x.report("oj")        { Oj.load(json_output) } if RUN[:oj]
      x.report("Oj::Parser") { Oj::Parser.new(:usual).parse(json_output) } if RUN[:oj]
    end

    if defined?(RapidJSON)
      x.report("rapidjson") { RapidJSON.parse(json_output) } if RUN[:rapidjson]
    end

    x.compare!(order: :baseline)
  end
  puts
end

# NB: Notes are based on ruby 3.3.4 (2024-07-09 revision be1089c8ec) +YJIT [arm64-darwin23]

benchmark_parsing "small nested array", JSON.dump([[1,2,3,4,5]]*10)
benchmark_parsing "small hash", JSON.dump({ "username" => "jhawthorn", "id" => 123, "event" => "wrote json serializer" })
benchmark_parsing "test from oj", <<JSON
{"a":"Alpha","b":true,"c":12345,"d":[true,[false,[-123456789,null],3.9676,["Something else.",false],null]],
"e":{"zero":null,"one":1,"two":2,"three":[3],"four":[0,1,2,3,4]},"f":null,
"h":{"a":{"b":{"c":{"d":{"e":{"f":{"g":null}}}}}}},"i":[[[[[[[null]]]]]]]}
JSON

# On these macro-benchmarks, we're on par with `Oj::Parser`, except `twitter.json` where we're `1.14x` faster,
# And between 1.3x and 1.5x faster than `Oj.load`.
benchmark_parsing "activitypub.json", File.read("#{__dir__}/data/activitypub.json")
benchmark_parsing "twitter.json", File.read("#{__dir__}/data/twitter.json")
benchmark_parsing "citm_catalog.json", File.read("#{__dir__}/data/citm_catalog.json")

# rapidjson is 8x faster thanks to its much more performant float parser.
# Unfortunately, there isn't a lot of existing fast float parsers in pure C,
# and including C++ is problematic.
# Aside from that, we're close to the alternatives here.
benchmark_parsing "float parsing", File.read("#{__dir__}/data/canada.json")
