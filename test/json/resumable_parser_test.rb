# frozen_string_literal: true
require_relative 'test_helper'

class JSONResumageParserTest < Test::Unit::TestCase
  include JSON

  def setup
    omit "JRuby not supported" if RUBY_ENGINE == "jruby"
    @parser = new_parser
  end

  def test_parse_document_direct
    @parser << '[true]'
    assert_equal true, @parser.parse
    assert_equal [true], @parser.value
  end

  def test_parse_multiple_documents_direct
    @parser << '[true]{}[1, 2, 3]'

    assert_equal true, @parser.parse
    assert_equal [true], @parser.value

    assert_equal true, @parser.parse
    assert_equal({}, @parser.value)

    assert_equal true, @parser.parse
    assert_equal [1, 2, 3], @parser.value
  end

  def test_parse_byte_by_byte_array
    assert_resumed_parsing('[]')
    assert_resumed_parsing('[    ]')
    assert_resumed_parsing('[true]')
    assert_resumed_parsing('[12]')
    assert_resumed_parsing('[ 12 ]')
    assert_resumed_parsing('[ 12.3 ]')
    assert_resumed_parsing('[ 12.3e12 ]')
    assert_resumed_parsing('[ 1e12 ]')
    assert_resumed_parsing('[-12]')
    assert_resumed_parsing('[ -12 ]')
    assert_resumed_parsing('[ -12.3 ]')
    assert_resumed_parsing('[ -12.3e12 ]')
    assert_resumed_parsing('[ -1e12 ]')
  end

  def test_parse_byte_by_byte_object
    assert_resumed_parsing('{}')
    assert_resumed_parsing('{    }')
    assert_resumed_parsing('{"test" : true}')
    assert_resumed_parsing('{  "test":12, "value" : { "key": 42}  }')
  end

  def test_parse_byte_by_byte_string
    assert_resumed_parsing(JSON.generate('test'))
    assert_resumed_parsing(JSON.generate('te\\st'))
    assert_resumed_parsing('"te\\u2028st"')
    assert_resumed_parsing(JSON.generate("te\u2028st"))
    assert_resumed_parsing(JSON.generate("te \u2028 st"))
  end

  def test_parse_byte_by_byte_numbers
    # TODO: should we actually types other than Array and Object and perhaps String at the top level?
    # Top values other than arrays and objects are ambiguous.
    # e.g. if you stream numbers without a separator: 123 followed by 456 is ambiguous with 123456.
    assert_resumed_parsing('123 ')
  end

  def test_rest
    @parser << '[1, 2, 3, "unterminated string'
    refute @parser.parse
    assert_equal '"unterminated string', @parser.rest
  end

  def test_partial_value
    assert_partial_value [1, 2, 3], '[1, 2, 3, "unterminated string'
    assert_partial_value({ "a" => 1, "b" => { "c" => nil } }, '{ "a": 1, "b": { "c": "unterminated string')
    assert_partial_value({ "a" => 1, "b" => { "c" => nil } }, '{ "a": 1, "b": { "c"')
    assert_partial_value([1, { "a" => 1, "b" => { "c" => nil } }], '[1, { "a": 1, "b": { "c"')
  end

  def test_partial_value_missing
    assert_nil @parser.partial_value
  end

  def test_assert_reentrency_prevented
    called = false
    parser = nil
    callback = ->(_o) do
      unless called
        called = true
        parser.parse
      end
    end
    parser = new_parser(on_load: callback)
    parser << '[]'
    error = assert_raise ArgumentError do
      parser.parse
    end
    assert_equal "ResumableParser can't be used recursively", error.message
  end

  private

  def assert_partial_value(expected, json)
    parser = new_parser
    parser << json
    refute parser.parse
    2.times do
      assert_equal expected, parser.partial_value
    end
  end

  def assert_resumed_parsing(json, parser = @parser)
    expected = JSON.parse(json)

    last_parsed_byte_index = 0
    json.each_byte do |byte|
      parser << byte.chr
      last_parsed_byte_index += 1
      break if parser.parse
    end
    actual = parser.value
    assert_equal expected, actual
    remaining_bytes = (json.bytesize - last_parsed_byte_index)
    assert_equal 0, remaining_bytes, "unconsumed bytes: #{actual.inspect}, remaining: #{json.byteslice(-1, remaining_bytes).inspect}"
  end

  def new_parser(options = {})
    JSON::Ext::ResumableParser.new(options)
  end
end
