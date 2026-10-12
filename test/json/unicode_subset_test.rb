# frozen_string_literal: true

require_relative 'test_helper'
require 'json/unicode_subset'

class JSONUnicodeSubsetTest < Test::Unit::TestCase
  # RFC 9839 section 4, expressed as allowed ranges independently of the classifier.
  RANGES = {
    scalars: [0..0xD7FF, 0xE000..0x10FFFF],
    xml_characters: [9..10, 13..13, 0x20..0xD7FF, 0xE000..0xFFFD, 0x10000..0x10FFFF],
    assignables: [9..10, 13..13, 0x20..0x7E, 0xA0..0xD7FF, 0xE000..0xFDCF, 0xFDF0..0xFFFD] +
      (1..16).map { |plane| (plane << 16)..((plane << 16) + 0xFFFD) },
  }.freeze

  def test_classifier_matches_rfc_abnf_for_every_codepoint
    RANGES.each do |subset, ranges|
      mask = JSON::UnicodeSubset.mask(subset)
      allowed = Array.new(0x110000, false)
      ranges.each { |range| range.each { |cp| allowed[cp] = true } }
      mismatch = (0..0x10FFFF).find do |cp|
        allowed[cp] != (JSON::UnicodeSubset.classify(cp) & mask == 0)
      end
      assert_nil mismatch, "#{subset}: #{mismatch && format('U+%04X', mismatch)}"
    end
  end

  def test_boundaries_and_regexps
    points = [0, 8, 9, 10, 11, 12, 13, 14, 31, 32, 0x7E, 0x7F, 0x80, 0x9F, 0xA0,
      0xD7FF, 0xE000, 0xFDCF, 0xFDD0, 0xFDEF, 0xFDF0, 0xFFFD, 0xFFFE, 0xFFFF,
      0x10000, 0x1FFFD, 0x1FFFE, 0x10FFFD, 0x10FFFE, 0x10FFFF]
    RANGES.each do |subset, ranges|
      points.each do |cp|
        char = [cp].pack('U')
        allowed = ranges.any? { |range| range.cover?(cp) }
        assert_equal allowed, !JSON::UnicodeSubset::PATTERNS.fetch(subset).match?(char)
        assert_equal allowed ? nil : [cp, 3], JSON::UnicodeSubset.violation("日#{char}", subset)
        assert_equal allowed ? char : "\uFFFD", JSON::UnicodeSubset.scrub(char, subset)
      end
    end
  end

  def test_malformed_utf8_uses_maximal_subparts
    ["\x80", "\xC0\xAF", "\xE1\x80", "\xED\xA0\x80", "\xF4\x90\x80\x80", "\xF0\x90a"].each do |string|
      RANGES.each_key do |subset|
        assert_equal [nil, 1], JSON::UnicodeSubset.violation("a#{string}", subset)
        assert_equal string.scrub, JSON::UnicodeSubset.scrub(string, subset)
      end
    end
  end

  def test_clean_string_is_not_copied
    string = 'hello 日本 😀'
    RANGES.each_key { |subset| assert_same string, JSON::UnicodeSubset.scrub(string, subset) }
  end

  def test_invalid_subset
    impersonator = Object.new
    def impersonator.hash; :scalars.hash; end
    def impersonator.eql?(other); true; end
    [false, true, 'scalars', :unknown, impersonator].each do |subset|
      assert_raise(ArgumentError) { JSON::UnicodeSubset.mask(subset) }
    end
  end
end
