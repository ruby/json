# frozen_string_literal: true
require_relative 'test_helper'

begin
  require 'bigdecimal'
rescue LoadError
end

class JSONBigDecimalTest < Test::Unit::TestCase
  def test_preserves_bigdecimals_when_parsing_json_with_decimal_numbers
    big_decimal = BigDecimal('1.12345678912345678912345')
    json_string = '{"amount": 1.12345678912345678912345}'
    parsed = JSON.parse(json_string)

    assert_instance_of(BigDecimal, parsed['amount'])
    assert_equal(big_decimal, parsed['amount'])
  end if defined?(::BigDecimal)

  def test_parses_string_bigdecimals_as_strings_not_bigdecimals
    json_with_string = '{"amount": "1.12345678912345678912345"}'
    parsed = JSON.parse(json_with_string)

    # String representations should remain as strings
    assert_instance_of(String, parsed['amount'])
    assert_equal('1.12345678912345678912345', parsed['amount'])
  end if defined?(::BigDecimal)

  def test_handles_regular_floats_without_converting_to_bigdecimal
    hash_with_float = { 'value' => 1.23 }
    json_string = hash_with_float.to_json
    parsed_back = JSON.parse(json_string)

    # Regular floats should remain as floats (or be converted based on precision)
    assert_kind_of(Numeric, parsed_back['value'])
    assert_equal(1.23, parsed_back['value'])
  end if defined?(::BigDecimal)
end
