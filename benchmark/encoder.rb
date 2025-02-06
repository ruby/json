require "benchmark/ips"
require "json"
require "date"
begin
  require "oj"
  Oj.default_options = Oj.default_options.merge(mode: :compat)
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

def implementations(ruby_obj)
  state = JSON::State.new(JSON.dump_default_options)
  coder = JSON::Coder.new
  implementations = {
    json: ["json", proc { JSON.generate(ruby_obj) }],
    json_coder: ["json_coder", proc { coder.dump(ruby_obj) }],
  }

  if defined?(Oj)
    implementations[:oj] = ["oj", proc { Oj.dump(ruby_obj) }]
  end

  implementations
end

def benchmark_encoding(benchmark_name, ruby_obj, check_expected: true, except: [])
  json_output = JSON.dump(ruby_obj)
  puts "== Encoding #{benchmark_name} (#{json_output.bytesize} bytes)"

  impls = implementations(ruby_obj).select { |name| RUN[name] }
  except.each { |i| impls.delete(i) }

  Benchmark.ips do |x|
    expected = ::JSON.dump(ruby_obj) if check_expected
    impls.values.each do |name, block|
      begin
        result = block.call
        if check_expected && expected != result
          puts "#{name} does not match expected output. Skipping"
          puts "Expected:" + '-' * 40
          puts expected
          puts "Actual:" + '-' * 40
          puts result
          puts '-' * 40
          next
        end
      rescue => error
        puts "#{name} unsupported (#{error})"
        next
      end
      x.report(name, &block)
    end
    x.compare!(order: :baseline)
  end
  puts
end

# NB: Notes are based on ruby 3.3.4 (2024-07-09 revision be1089c8ec) +YJIT [arm64-darwin23]

# On the first two micro benchmarks, the limitting factor is the fixed cost of initializing the
# generator state. Since `JSON.generate` now lazily allocate the `State` object we're now ~10-20% faster
# than `Oj.dump`.
benchmark_encoding "small mixed", [1, "string", { a: 1, b: 2 }, [3, 4, 5]]
benchmark_encoding "small nested array", [[1,2,3,4,5]]*10
benchmark_encoding "small hash", { "username" => "jhawthorn", "id" => 123, "event" => "wrote json serializer" }

# On string encoding we're ~20% faster when dealing with mostly ASCII, but ~50% slower when dealing
# with mostly multi-byte characters. There's likely some gains left to be had in multi-byte handling.
benchmark_encoding "mixed utf8", ([("a" * 5000) + "€" + ("a" * 5000)] * 500)
benchmark_encoding "mostly utf8", ([("€" * 3333)] * 500)

# On these benchmarks we perform well, we're on par or a bit better.
benchmark_encoding "integers", (1_000_000..1_001_000).to_a, except: %i(json_state)
benchmark_encoding "activitypub.json", JSON.load_file("#{__dir__}/data/activitypub.json")
benchmark_encoding "citm_catalog.json", JSON.load_file("#{__dir__}/data/citm_catalog.json")
benchmark_encoding "twitter.json", JSON.load_file("#{__dir__}/data/twitter.json")

# This benchmark spent the overwhelming majority of its time in `ruby_dtoa`. We rely on Ruby's implementation
# which uses a relatively old version of dtoa.c from David M. Gay.
# Oj in `compat` mode is ~10% slower than `json`, but in its default mode is noticeably faster here because
# it limits the precision of floats, breaking roundtriping.  That's not something we should emulate.
#
# Since a few years there are now much faster float to string implementations such as Ryu, Dragonbox, etc,
# but all these are implemented in C++11 or newer, making it hard if not impossible to include them.
# Short of a pure C99 implementation of these newer algorithms, there isn't much that can be done to match
# Oj speed without losing precision.
benchmark_encoding "canada.json", JSON.load_file("#{__dir__}/data/canada.json"), check_expected: false

# We're about 10% faster when `to_json` calls are involved, but this wasn't particularly optimized, there might be
# opportunities here.
benchmark_encoding "many #to_json calls", [{object: Object.new, int: 12, float: 54.3, class: Float, time: Time.now, date: Date.today}] * 20
