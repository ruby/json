# frozen_string_literal: true

require_relative 'test_helper'

class JSONUnicodeGeneratorTest < Test::Unit::TestCase
  SUBSETS = [:scalars, :xml_characters, :assignables].freeze
  BOUNDARIES = [
    0, 8, 9, 10, 11, 12, 13, 14, 31, 32, 126, 127, 128, 159, 160,
    0xD7FF, 0xE000, 0xFDCF, 0xFDD0, 0xFDEF, 0xFDF0, 0xFFFD,
    0xFFFE, 0xFFFF, 0x10000, 0x1FFFD, 0x1FFFE, 0x10FFFD, 0x10FFFE, 0x10FFFF,
  ].freeze

  def test_options
    state = JSON::State.new
    assert_nil state.unicode_subset
    assert_equal :raise, state.on_invalid_char
    assert_nil state.to_h[:unicode_subset]
    assert_equal :raise, state.to_h[:on_invalid_char]

    state.configure(unicode_subset: :assignables, on_invalid_char: :replace)
    assert_equal :assignables, state.unicode_subset
    assert_equal :replace, state.on_invalid_char
    assert_equal state.to_h, JSON::State.new(state.to_h).to_h
    assert_equal state.to_h, state.dup.to_h
    assert_equal '"�"', state.freeze.generate("\u0089")
    assert_raise(FrozenError) { state.unicode_subset = nil }
    assert_raise(FrozenError) { state.on_invalid_char = :raise }
  end

  def test_invalid_option_values
    impostor = Object.new
    def impostor.equal?(_other)
      true
    end
    [false, true, 0, 'scalars', :scalar, Object.new].each do |value|
      assert_raise(ArgumentError) { JSON.generate(nil, unicode_subset: value) }
      assert_raise(ArgumentError) { JSON::State.new.unicode_subset = value }
    end
    [nil, false, true, 0, 'replace', :ignore, Object.new, impostor].each do |value|
      assert_raise(ArgumentError) { JSON.generate(nil, on_invalid_char: value) }
      assert_raise(ArgumentError) { JSON::State.new.on_invalid_char = value }
    end
  end

  def test_boundaries
    SUBSETS.each do |subset|
      BOUNDARIES.each do |cp|
        string = cp.chr(Encoding::UTF_8).freeze
        allowed = case subset
        when :scalars
          true
        when :xml_characters
          cp == 9 || cp == 10 || cp == 13 || (cp >= 32 && cp != 0xFFFE && cp != 0xFFFF)
        when :assignables
          cp == 9 || cp == 10 || cp == 13 ||
            (cp >= 32 && !(127..159).cover?(cp) && !(0xFDD0..0xFDEF).cover?(cp) && (cp & 0xFFFF) < 0xFFFE)
        end
        [{}, {ascii_only: true}, {script_safe: true}, {max_nesting: 0}].each do |format|
          options = format.merge(unicode_subset: subset)
          if allowed
            assert_equal string, JSON.parse(JSON.generate(string, **options))
            assert_equal({string => string}, JSON.parse(JSON.generate({string => string}, **options)))
          else
            error = assert_raise(JSON::GeneratorError) { JSON.generate(string, **options) }
            assert_same string, error.invalid_object
            assert_equal 'U+%04X is not allowed by unicode_subset: :%s' % [cp, subset], error.message
            error = assert_raise(JSON::GeneratorError) { JSON.generate({string => 1}, **options) }
            assert_same string, error.invalid_object
          end
          replacement = allowed ? string : "\uFFFD"
          options[:on_invalid_char] = :replace
          assert_equal replacement, JSON.parse(JSON.generate(string, **options))
          assert_equal({replacement => replacement}, JSON.parse(JSON.generate({string => string}, **options)))
        end
      end
    end
  end

  def test_malformed_utf8_maximal_subparts
    cases = {
      [0xFF] => "�",
      [0x80] => "�",
      [0xC0, 0xAF] => "��",
      [0xE1, 0x80] => "�",
      [0xE1, 0x80, 0x41] => "�A",
      [0xED, 0xA0, 0x80] => "���",
      [0xF0, 0x90, 0x80] => "�",
      [0xF4, 0x90, 0x80, 0x80] => "����",
      [0xF5, 0x80, 0x80, 0x80] => "����",
    }
    cases.each do |bytes, replacement|
      [Encoding::UTF_8, Encoding::BINARY].each do |encoding|
        original = bytes.pack('C*').force_encoding(encoding).freeze
        SUBSETS.each do |subset|
          error = assert_raise(JSON::GeneratorError) { JSON.generate(original, unicode_subset: subset) }
          assert_same original, error.invalid_object
          assert_equal "invalid UTF-8 sequence is not allowed by unicode_subset: :#{subset}", error.message
          options = {unicode_subset: subset, on_invalid_char: :replace}
          assert_equal replacement, JSON.parse(JSON.generate(original, **options))
          assert_equal({replacement => 1}, JSON.parse(JSON.generate({original => 1}, **options)))
          assert_equal replacement, JSON.parse(original.to_json(options))
          assert_equal replacement, JSON::Coder.new(**options).load(JSON::Coder.new(**options).dump(original))
          assert_equal bytes, original.bytes
          assert_equal encoding, original.encoding
        end
      end
    end
  end

  def test_forbidden_characters_at_scan_boundaries
    [0, 0x7F, 0x80, 0x9F, 0xFDD0, 0xFDEF, 0xFFFE, 0x1FFFE, 0x10FFFF].each do |cp|
      32.times do |offset|
        string = (("a" * offset) + cp.chr(Encoding::UTF_8) + "日本語\n" + ("z" * 32)).freeze
        error = assert_raise(JSON::GeneratorError) { JSON.generate(string, unicode_subset: :assignables) }
        assert_same string, error.invalid_object
        assert_equal 'U+%04X is not allowed by unicode_subset: :assignables' % cp, error.message
      end
    end
  end

  def test_io_callback_cannot_disable_validation_of_current_string
    string = (("a" * 64) + "\n" + ("a" * 64) + "\u0089").freeze
    [string, [nil, nil, nil, string]].each do |object|
      state = JSON::State.new(unicode_subset: :assignables, buffer_initial_length: 16)
      io = StringIO.new
      io.define_singleton_method(:write) do |part|
        state.unicode_subset = nil
        super(part)
      end
      error = assert_raise(JSON::GeneratorError) { state.generate(object, io) }
      assert_same string, error.invalid_object
      assert_equal 'U+0089 is not allowed by unicode_subset: :assignables', error.message
    end
  end

  def test_replacement_across_generation_entry_points
    options = {unicode_subset: :assignables, on_invalid_char: :replace}
    string = "a\u0000\u0089\u{7FFFF}z"
    object = {string => [string]}
    expected = '{"a���z":["a���z"]}'
    assert_equal expected, JSON.generate(object, **options)
    assert_equal expected, JSON.dump(object, **options)
    assert_equal expected, object.to_json(options)
    assert_equal expected, JSON::Coder.new(**options).dump(object)
    assert_equal expected, JSON::State.new(options).generate(object)
    assert_equal expected, JSON.generate(object, **options, max_nesting: 0)
    io = StringIO.new
    assert_same io, JSON.dump(object, io, **options)
    assert_equal expected, io.string
    io = StringIO.new
    assert_same io, JSON::Coder.new(**options).dump(object, io)
    assert_equal expected, io.string
    assert_equal '"�"', JSON.generate(:"\u0089", **options, strict: true)
    assert_equal '"�"', JSON.generate(:"\u0089", **options)
    object = Object.new
    def object.to_s
      "\u0089"
    end
    assert_equal '"�"', JSON.generate(object, **options)
    assert_equal '"�"', object.to_json(options)
  end

  def test_transcoded_strings
    [Encoding::UTF_16LE, Encoding::UTF_16BE, Encoding::ISO_8859_1].each do |encoding|
      string = "é\u0089".encode(encoding).freeze
      options = {unicode_subset: :assignables}
      error = assert_raise(JSON::GeneratorError) { JSON.generate(string, **options) }
      assert_same string, error.invalid_object
      assert_equal 'U+0089 is not allowed by unicode_subset: :assignables', error.message
      assert_equal '"é�"', JSON.generate(string, **options, on_invalid_char: :replace)
      assert_equal '{"é�":1}', JSON.generate({string => 1}, **options, on_invalid_char: :replace)
    end
    assert_raise(JSON::GeneratorError) { JSON.generate("é".b, unicode_subset: :scalars, on_invalid_char: :replace) }
  end

  def test_encoding_callback_precedes_replacement
    string = "\xFF".dup.force_encoding(Encoding::UTF_8)
    calls = []
    coder = JSON::Coder.new(unicode_subset: :assignables, on_invalid_char: :replace) do |object|
      calls << object
      "from callback\u0089"
    end
    assert_equal '"from callback�"', coder.dump(string)
    assert_equal [string], calls

    calls.clear
    assert_equal '["from callback�"]', coder.dump([string])
    assert_equal [string], calls
    calls.clear
    assert_equal '{"value":"from callback�"}', coder.dump({'value' => string})
    assert_equal [string], calls

    calls.clear
    assert_equal '{"from callback�":1}', coder.dump({string => 1})
    assert_equal [string], calls

    coder = JSON::Coder.new(unicode_subset: :assignables, on_invalid_char: :replace) { |object| object }
    assert_equal '"�"', coder.dump(string)
    coder = JSON::Coder.new(unicode_subset: :assignables, on_invalid_char: :replace) { 42 }
    assert_equal '42', coder.dump(string)

    string.freeze
    coder = JSON::Coder.new(unicode_subset: :assignables) { "\u0089" }
    error = assert_raise(JSON::GeneratorError) { coder.dump(string) }
    assert_same string, error.invalid_object
    error = assert_raise(JSON::GeneratorError) { coder.dump({string => 1}) }
    assert_same string, error.invalid_object
  end

  def test_malformed_string_returned_by_callback
    omit 'Callback invocation counts are specific to the C generator' unless RUBY_ENGINE == 'ruby'

    object = Object.new
    string = "\xFF".dup.force_encoding(Encoding::UTF_8).freeze
    calls = []
    coder = JSON::Coder.new(unicode_subset: :assignables, on_invalid_char: :replace) do |value|
      calls << value
      string
    end
    assert_equal '"�"', coder.dump(object)
    assert_equal [object], calls

    [nil, :assignables].each do |subset|
      calls.clear
      coder = JSON::Coder.new(unicode_subset: subset) do |value|
        calls << value
        string
      end
      error = assert_raise(JSON::GeneratorError) { coder.dump(object) }
      assert_same string, error.invalid_object
      assert_equal [object], calls
    end
  end

  def test_string_subclasses_returned_by_encoding_callback
    subclass = Class.new(String)
    invalid = "\xFF".dup.force_encoding(Encoding::UTF_8).freeze

    SUBSETS.each do |subset|
      calls = []
      coder = JSON::Coder.new(unicode_subset: subset) do |value|
        calls << value
        subclass.new('ok')
      end
      assert_equal '["ok"]', coder.dump([invalid])
      assert_equal [invalid], calls
      state = JSON::State.new(unicode_subset: subset, strict: true)
      state.as_json = proc do |_value|
        state.unicode_subset = nil
        subclass.new('ok')
      end
      assert_equal '["ok"]', state.generate([invalid])
    end
  end

  def test_replacement_key_collisions
    options = {unicode_subset: :assignables, on_invalid_char: :replace}
    [
      {"\u0089" => 1, "\u008A" => 2},
      {"\u0089" => 1, "�" => 2},
      {"\u0089" => 1, :"�" => 2},
      {"\xFF".dup.force_encoding(Encoding::UTF_8) => 1, "�" => 2},
    ].each do |object|
      [{}, {max_nesting: 0}, {ascii_only: true}].each do |format|
        merged = options.merge(format)
        error = assert_raise(JSON::GeneratorError) { JSON.generate(object, **merged) }
        assert_same object, error.invalid_object
        assert_equal "detected duplicate key #{'�'.inspect} in #{object.inspect}", error.message
        error = assert_raise(JSON::GeneratorError) { object.to_json(merged) }
        assert_same object, error.invalid_object
        assert_equal "detected duplicate key #{'�'.inspect} in #{object.inspect}", error.message
        output = JSON.generate(object, **merged, allow_duplicate_key: true)
        assert_equal '{"�":1,"�":2}', output.gsub('\\ufffd', '�')
      end
      error = assert_raise(JSON::GeneratorError) { JSON::Coder.new(**options).dump(object) }
      assert_same object, error.invalid_object
      assert_equal "detected duplicate key #{'�'.inspect} in #{object.inspect}", error.message
    end
    object = {"\u0089" => 1, "safe" => {"\u0089" => 2}}
    assert_equal '{"�":1,"safe":{"�":2}}', JSON.generate(object, **options)
  end

  def test_rfc8785_rejects_replacement
    [nil, *SUBSETS].each do |subset|
      assert_raise(ArgumentError) { JSON.generate(nil, rfc8785: true, unicode_subset: subset, on_invalid_char: :replace) }
      assert_raise(ArgumentError) { JSON::State.new(on_invalid_char: :replace, unicode_subset: subset, rfc8785: true) }
    end
    state = JSON::State.new(rfc8785: true)
    assert_raise(ArgumentError) { state.on_invalid_char = :replace }
    state = JSON::State.new(on_invalid_char: :replace)
    assert_raise(ArgumentError) { state.rfc8785 = true }
    state = JSON::State.new
    assert_raise(ArgumentError) { state.configure(rfc8785: true, on_invalid_char: :replace) }
    assert_raise(ArgumentError) { state.generate(nil) }
    assert_equal '"é"', JSON.generate('é', rfc8785: true, unicode_subset: :assignables)
    assert_raise(JSON::GeneratorError) { JSON.generate("\u0089", rfc8785: true, unicode_subset: :assignables) }
    ["\u0089", "\xFF".dup.force_encoding(Encoding::UTF_8)].each do |key|
      key.freeze
      error = assert_raise(JSON::GeneratorError) { JSON.generate({key => 1}, rfc8785: true, unicode_subset: :assignables) }
      assert_same key, error.invalid_object
      assert_match(/not allowed by unicode_subset: :assignables/, error.message)
    end
  end

  def test_rfc8785_with_converted_keys
    key = Object.new
    def key.to_s
      raise 'must not validate or sort the original key'
    end
    converted_key = 'a'
    as_json = ->(object, is_key) { is_key ? converted_key : object }
    options = {rfc8785: true, strict: true, as_json: as_json, unicode_subset: :assignables}
    object = [{'b' => 2, key => 1}]
    generators = [
      -> { JSON.generate(object, options) },
      -> { object.to_json(options) },
      -> { JSON::Coder.new(**options).dump(object) },
      -> {
        io = StringIO.new
        JSON::State.new(options).generate(object, io)
        io.string
      },
    ]
    generators.each { |generate| assert_equal '[{"a":1,"b":2}]', generate.call }

    converted_key = "\u0089"
    generators.each do |generate|
      error = assert_raise(JSON::GeneratorError) { generate.call }
      assert_equal converted_key, error.invalid_object
      assert_match(/U\+0089 is not allowed by unicode_subset: :assignables/, error.message)
    end
  end

  def test_no_subset_preserves_generation
    string = "\u0000\u0089\u{7FFFF}"
    assert_equal JSON.generate(string), JSON.generate(string, unicode_subset: nil, on_invalid_char: :replace)
    assert_raise(JSON::GeneratorError) { JSON.generate("\xFF".dup.force_encoding(Encoding::UTF_8), on_invalid_char: :replace) }
    assert_equal '"é"', JSON.generate('é', unicode_subset: nil)
    if RUBY_ENGINE == 'truffleruby'
      object = Object.new
      def object.to_s
        'é/'
      end
      assert_equal '"é/"', JSON.generate(object, ascii_only: true, script_safe: true)
      assert_equal '"é/"', object.to_json(ascii_only: true, script_safe: true)
    end
  end
end
