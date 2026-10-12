# frozen_string_literal: true

require_relative 'test_helper'

class JSONUnicodeParserTest < Test::Unit::TestCase
  SUBSETS = [:scalars, :xml_characters, :assignables].freeze
  BOUNDARIES = [0, 8, 9, 10, 11, 12, 13, 14, 31, 32, 126, 127, 128, 159, 160,
    0xD7FF, 0xE000, 0xFDCF, 0xFDD0, 0xFDEF, 0xFDF0, 0xFFFD, 0xFFFE, 0xFFFF,
    0x10000, 0x1FFFD, 0x1FFFE, 0x1FFFF, 0x10FFFD, 0x10FFFE, 0x10FFFF].freeze

  def test_subset_boundaries
    SUBSETS.each do |subset|
      BOUNDARIES.each do |cp|
        char = cp.chr(Encoding::UTF_8)
        [char, unicode_escape(cp)].each do |source|
          [false, true].each do |key|
            json = key ? %({"#{source}":1}) : %(["#{source}"])
            options = { unicode_subset: subset, allow_control_characters: true }
            expected = allowed?(cp, subset) ? char : "\uFFFD"
            result = key ? { expected => 1 } : [expected]
            assert_equal result, JSON.parse(json, **options, on_invalid_char: :replace), [subset, cp, key, source].inspect
            if allowed?(cp, subset)
              assert_equal result, JSON.parse(json, **options)
            else
              error = assert_raise(JSON::ParserError) { JSON.parse(json, **options) }
              assert_include error.message, 'U+%04X' % cp
              assert_include error.message, "unicode_subset: :#{subset}"
            end
          end
        end
      end
    end
  end

  def test_key_cache_after_subset_changes
    omit 'Parser config reinitialization is specific to the C extension' if RUBY_ENGINE == 'jruby'

    config = nil
    on_load = lambda do |value|
      config.send(:initialize, unicode_subset: :assignables) if value == 0
      value
    end
    config = JSON::Parser::Config.new(unicode_subset: :scalars, on_load: on_load)
    error = assert_raise(JSON::ParserError) { config.parse(%([{"a\u0085":0},{"a\u0085":1}])) }
    assert_include error.message, 'U+0085'

    on_load = lambda do |value|
      if value == 0
        config.send(:initialize, unicode_subset: nil, on_load: on_load)
      elsif value == 1
        config.send(:initialize, unicode_subset: :assignables)
      end
      value
    end
    config = JSON::Parser::Config.new(unicode_subset: :assignables, on_load: on_load)
    error = assert_raise(JSON::ParserError) { config.parse(%([{"a":0},{"a\u0085":1},{"a\u0085":2}])) }
    assert_include error.message, 'U+0085'

    on_load = lambda do |value|
      config.send(:initialize, unicode_subset: nil, symbolize_names: false) if value == 0
      value
    end
    config = JSON::Parser::Config.new(unicode_subset: :assignables, symbolize_names: true, on_load: on_load)
    assert_equal [{ a: 0 }, { 'a' => 1 }], config.parse('[{"a":0},{"a":1}]')
  end

  def test_invalid_utf8_maximal_subparts
    invalid = ["\x80", "\xC0\xAF", "\xE0\x80\x80", "\xED\xA0\x80", "\xF4\x90\x80\x80",
      "\xF5\x80\x80\x80", "\xE1", "\xE1\x80", "\xF1\x80\x80", "\xE1\x80z", "\xC2z"]
    SUBSETS.each do |subset|
      invalid.each do |bytes|
        [bytes, "a\\tb#{bytes}c", "#{bytes}\\nc"].each do |source|
          [false, true].each do |key|
            json = key ? %({"#{source}":1}) : %(["#{source}"])
            error = assert_raise(JSON::ParserError) { JSON.parse(json, unicode_subset: subset) }
            assert_include error.message, 'invalid UTF-8'
            expected = JSON.parse(json.scrub, unicode_subset: subset)
            assert_equal expected, JSON.parse(json, unicode_subset: subset, on_invalid_char: :replace)
          end
        end
      end
    end
  end

  def test_orphan_surrogates
    {
      '\uD800' => "\uFFFD", '\uDFFF' => "\uFFFD", '\uD800x' => "\uFFFDx",
      '\uD800\u0041' => "\uFFFDA", '\uD800\uD800\uDC00' => "\uFFFD\u{10000}",
      '\uDC00\uD800' => "\uFFFD\uFFFD", '\uD800\n' => "\uFFFD\n",
    }.each do |source, expected|
      [nil, *SUBSETS].each do |subset|
        assert_equal expected, JSON.parse(%("#{source}"), unicode_subset: subset, on_invalid_char: :replace)
        assert_raise(JSON::ParserError) { JSON.parse(%("#{source}"), unicode_subset: subset) }
      end
    end
    ['\uD800\uZZZZ', '\uZZZZ', '\uD800\u123', '\u123'].each do |source|
      assert_raise(JSON::ParserError) { JSON.parse(%("#{source}"), on_invalid_char: :replace) }
    end
  end

  def test_default_and_nil_subset_compatibility
    source = "[\"\xED\xA0\x80\u0089\uFFFF\"]"
    expected = JSON.parse(source)
    assert_equal expected, JSON.parse(source, unicode_subset: nil)
    assert_equal expected, JSON.parse(source, on_invalid_char: :replace)
    assert_equal ["\u0000\u0089\u{7FFFF}"], JSON.parse('["\u0000\u0089\uD9BF\uDFFF"]')
  end

  def test_option_validation
    [false, true, 0, 'scalars', :unknown].each do |value|
      assert_raise(ArgumentError) { JSON.parse('null', unicode_subset: value) }
    end
    [nil, false, true, 0, 'replace', :unknown].each do |value|
      assert_raise(ArgumentError) { JSON.parse('null', on_invalid_char: value) }
    end
  end

  def test_source_positions_and_path
    ['\u0089', "\u0089"].each do |source|
      error = assert_raise(JSON::ParserError) do
        JSON.parse("{\"outer\": [\n\"日#{source}\"]}", unicode_subset: :assignables)
      end
      assert_equal 2, error.line
      assert_equal 3, error.column
      assert_equal '$.outer[0]', error.json_path
    end
    error = assert_raise(JSON::ParserError) do
      JSON.parse('{"outer":{"a\uFFFF":0}}', unicode_subset: :assignables)
    end
    assert_equal '$.outer', error.json_path
    assert_equal 13, error.column
  end

  def test_replacement_before_symbolizing_freezing_and_duplicate_detection
    result = JSON.parse('[{"a\u0089":"b\uFFFF"},{"a\u0089":"b\uFFFF"}]',
      unicode_subset: :assignables, on_invalid_char: :replace, symbolize_names: true, freeze: true)
    assert_equal [{ :"a\uFFFD" => "b\uFFFD" }, { :"a\uFFFD" => "b\uFFFD" }], result
    assert_predicate result[0].values[0], :frozen?
    assert_raise(JSON::ParserError) do
      JSON.parse('{"\u0089":1,"\uFFFF":2}', unicode_subset: :assignables, on_invalid_char: :replace)
    end
    assert_equal({ "\uFFFD" => 2 }, JSON.parse('{"\u0089":1,"\uFFFF":2}',
      unicode_subset: :assignables, on_invalid_char: :replace, allow_duplicate_key: true))
  end

  def test_permissive_syntax_options_do_not_bypass_subset
    assert_raise(JSON::ParserError) { JSON.parse("\"\x01\"", unicode_subset: :assignables) }
    assert_equal "\uFFFD", JSON.parse("\"\x01\"", unicode_subset: :assignables,
      allow_control_characters: true, on_invalid_char: :replace)
    assert_equal "é", JSON.parse('"\é"', unicode_subset: :assignables, allow_invalid_escape: true)
    assert_equal "\uFFFD", JSON.parse("\"\\\u0089\"", unicode_subset: :assignables,
      allow_invalid_escape: true, on_invalid_char: :replace)
    error = assert_raise(JSON::ParserError) do
      JSON.parse("[\"\\\u0089\"]", unicode_subset: :assignables, allow_invalid_escape: true)
    end
    assert_equal 3, error.column
    ["\x00", "\x01"].each do |char|
      assert_raise(JSON::ParserError) do
        JSON.parse("\"\\#{char}\"", unicode_subset: :scalars, allow_invalid_escape: true)
      end
      assert_equal char, JSON.parse("\"\\#{char}\"", unicode_subset: :scalars,
        allow_invalid_escape: true, allow_control_characters: true)
    end
    assert_equal "\n\r\t\uFFFD\uFFFD", JSON.parse('"\n\r\t\b\f"',
      unicode_subset: :assignables, on_invalid_char: :replace)
  end

  def test_resumable_closed_incomplete_unicode_escape
    omit 'JRuby does not implement ResumableParser' if RUBY_ENGINE == 'jruby'
    ['"\u123"', '"\uD800\u123"'].each do |source|
      [{ unicode_subset: :scalars }, { on_invalid_char: :replace }].each do |options|
        parser = JSON::ResumableParser.new(**options)
        parser << source
        assert_raise(JSON::ParserError) { parser.parse }
      end
    end
  end

  def test_resumable_chunk_boundaries
    omit 'JRuby does not implement ResumableParser' if RUBY_ENGINE == 'jruby'
    sources = ['{"a\u0089":["\uD800\u0041","\uD9BF\uDFFF"]}',
      "{\"日\u0089\":[\"\xE1\x80z\u{1FFFF}\"]}"]
    sources.each do |source|
      expected = JSON.parse(source, unicode_subset: :assignables, on_invalid_char: :replace)
      (0..source.bytesize).each do |split|
        parser = JSON::ResumableParser.new(unicode_subset: :assignables, on_invalid_char: :replace)
        parser << source.byteslice(0, split)
        complete = parser.parse
        parser << source.byteslice(split..-1)
        assert parser.parse unless complete
        assert_equal expected, parser.value
      end
    end
  end

  def test_resumable_source_positions_after_buffer_compaction
    omit 'JRuby does not implement ResumableParser' if RUBY_ENGINE == 'jruby'
    ['["日",' + '"x",' * 140 + "\n" + '"日\uFFFF"]',
      '["日",' + '"x",' * 140 + '"日\uFFFF"]'].each do |source|
      expected = assert_raise(JSON::ParserError) { JSON.parse(source, unicode_subset: :assignables) }
      [false, true].each do |frozen_chunks|
        (0..source.bytesize).each do |split|
          parser = JSON::ResumableParser.new(unicode_subset: :assignables)
          error = assert_raise(JSON::ParserError) do
            [source.byteslice(0, split), source.byteslice(split..-1)].each do |chunk|
              parser << (frozen_chunks ? chunk.freeze : chunk)
              parser.parse
            end
          end
          assert_equal [expected.line, expected.column, expected.json_path],
            [error.line, error.column, error.json_path], [split, frozen_chunks].inspect
        end
      end
    end
  end

  private

  def unicode_escape(cp)
    return '\u%04X' % cp if cp <= 0xFFFF
    cp -= 0x10000
    '\u%04X\u%04X' % [0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)]
  end

  def allowed?(cp, subset)
    return true if subset == :scalars
    return false if cp < 32 && ![9, 10, 13].include?(cp)
    return false if cp == 0xFFFE || cp == 0xFFFF
    return true if subset == :xml_characters
    !(127..159).cover?(cp) && !(0xFDD0..0xFDEF).cover?(cp) && (cp & 0xFFFF) < 0xFFFE
  end
end
