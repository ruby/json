# frozen_string_literal: true

require_relative 'test_helper'

class JSONUnicodeIntegrationTest < Test::Unit::TestCase
  SUBSETS = [:scalars, :xml_characters, :assignables].freeze

  def test_coder_applies_options_to_both_directions
    SUBSETS.each do |subset|
      coder = JSON::Coder.new(unicode_subset: subset, on_invalid_char: :replace)
      string = "\u0000\u0089\u{7FFFF}"
      expected = JSON::UnicodeSubset.scrub(string, subset)
      assert_equal expected, coder.load(coder.dump(string))
      assert_equal expected, coder.load('"\u0000\u0089\uD9BF\uDFFF"')
      io = StringIO.new
      assert_same io, coder.dump(string, io)
      assert_equal expected, JSON.parse(io.string)
    end
  end

  def test_load_dump_and_direct_to_json
    options = { unicode_subset: :assignables, on_invalid_char: :replace }
    object = { "a\u0089" => ["\u0000日\uFFFF"] }
    expected = { "a\uFFFD" => ["\uFFFD日\uFFFD"] }
    assert_equal expected, JSON.load(JSON.generate(object), nil, **options)
    assert_equal expected, JSON.parse(JSON.dump(object, **options))
    assert_equal expected, JSON.parse(object.to_json(options))
    assert_equal expected, JSON.parse(JSON.generate(object, **options, max_nesting: false))
    assert_equal expected, JSON.parse(JSON.generate(object, **options, ascii_only: true))
    assert_equal expected, JSON.parse(JSON.generate(object, **options, script_safe: true))
  end

  def test_rfc_example
    source = '{"example": "\u0000\u0089\uDEAD\uD9BF\uDFFF"}'
    {
      scalars: "\u0000\u0089\uFFFD\u{7FFFF}",
      xml_characters: "\uFFFD\u0089\uFFFD\u{7FFFF}",
      assignables: "\uFFFD" * 4,
    }.each do |subset, expected|
      assert_raise(JSON::ParserError) { JSON.parse(source, unicode_subset: subset) }
      assert_equal({ 'example' => expected }, JSON.parse(source, unicode_subset: subset, on_invalid_char: :replace))
    end
  end

  def test_replacement_precedes_symbolization_freezing_and_duplicate_detection
    options = { unicode_subset: :assignables, on_invalid_char: :replace }
    result = JSON.parse('{"a\u0089":"\uFFFF"}', **options, symbolize_names: true, freeze: true)
    assert_equal({ :"a\uFFFD" => "\uFFFD" }, result)
    assert_predicate result, :frozen?
    assert_predicate result.values.first, :frozen?
    assert_raise(JSON::ParserError) { JSON.parse('{"\u0000":1,"\u0089":2}', **options) }
    assert_equal({ "\uFFFD" => 2 }, JSON.parse('{"\u0000":1,"\u0089":2}', **options, allow_duplicate_key: true))
    assert_raise(JSON::GeneratorError) { JSON.generate({ "\u0000" => 1, "\u0089" => 2 }, **options) }
    assert_equal "{\"\uFFFD\":1,\"\uFFFD\":2}", JSON.generate({ "\u0000" => 1, "\u0089" => 2 }, **options, allow_duplicate_key: true)
  end

  def test_nil_subset_replaces_only_unpaired_surrogate_escapes
    {
      '"\uD800"' => "\uFFFD",
      '"\uDC00"' => "\uFFFD",
      '"\uD800a"' => "\uFFFDa",
      '"\uD800\u0041"' => "\uFFFDA",
      '"\uD800\uD800\uDC00"' => "\uFFFD\u{10000}",
      '"\u0000\u0089\uFFFF"' => "\u0000\u0089\uFFFF",
    }.each do |source, expected|
      assert_equal expected, JSON.parse(source, on_invalid_char: :replace)
    end
    ['"\uD800\uZZZZ"', '"\uD800\u"', '"\uD800\\"'].each do |source|
      assert_raise(JSON::ParserError) { JSON.parse(source, on_invalid_char: :replace) }
    end
    assert_raise(JSON::GeneratorError) { JSON.generate("\xED\xA0\x80", on_invalid_char: :replace) }
  end

  def test_error_location_and_path
    ['\\u0089', "\u0089"].each do |bad|
      error = assert_raise(JSON::ParserError) do
        JSON.parse("{\n  \"a\": [\"日#{bad}\"]}", unicode_subset: :assignables)
      end
      assert_equal 2, error.line
      assert_equal 11, error.column
      assert_equal '$.a[0]', error.json_path
      assert_include error.message, 'U+0089'
      assert_include error.message, 'unicode_subset: :assignables'
    end
  end

  def test_all_scalar_codepoints_match_reference
    # Includes unassigned and private-use codepoints, which all subsets permit.
    string = ((0..0xD7FF).to_a + (0xE000..0x10FFFF).to_a).pack('U*')
    source = JSON.generate(string)
    SUBSETS.each do |subset|
      expected = JSON::UnicodeSubset.scrub(string, subset)
      options = { unicode_subset: subset, on_invalid_char: :replace }
      assert_equal expected, JSON.parse(source, **options)
      assert_equal expected, JSON.parse(JSON.generate(string, **options))
      assert_equal expected, JSON.parse(JSON.generate(expected, unicode_subset: subset))
    end
  end

  def test_random_malformed_utf8_matches_reference
    random = Random.new(9839)
    SUBSETS.each do |subset|
      100.times do
        string = Array.new(30) { random.rand(0x20..0xFF) }.pack('C*').force_encoding(Encoding::UTF_8)
        source = '"' + string.b.gsub(/["\\]/n) { |char| '\\' + char } + '"'
        expected = JSON::UnicodeSubset.scrub(string, subset)
        options = { unicode_subset: subset, on_invalid_char: :replace }
        assert_equal expected, JSON.parse(source, **options)
        assert_equal expected, JSON.parse(JSON.generate(string, **options))
      end
    end
  end

  def test_canonical_key_validation
    ["\u0089", "\xFF", "\xFF".b, "é".b].each do |key|
      assert_raise(JSON::GeneratorError) do
        JSON.generate({ key => 1 }, rfc8785: true, unicode_subset: :assignables)
      end
    end
  end

  def test_malformed_utf8_after_multibyte_runs
    prefix = "\u1000日\uCFFF" * 8
    ["\xE1", "\xEC\x80", "\xE1\x80 ", "\xEC \x80"].each do |invalid|
      ["", "日本"].each do |suffix|
        string = prefix + invalid + suffix
        source = '"' + string + '"'
        SUBSETS.each do |subset|
          options = { unicode_subset: subset }
          assert_raise(JSON::ParserError) { JSON.parse(source, **options) }
          assert_raise(JSON::GeneratorError) { JSON.generate(string, **options) }
          assert_equal string.scrub, JSON.parse(source, **options, on_invalid_char: :replace)
          assert_equal string.scrub, JSON.parse(JSON.generate(string, **options, on_invalid_char: :replace))
        end
      end
    end
  end
end
