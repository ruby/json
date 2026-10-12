# frozen_string_literal: true

module JSON
  # Internal reference implementation of RFC 9839 section 4.
  module UnicodeSubset # :nodoc:
    ILL_FORMED = 1
    SURROGATE = 2
    LEGACY_C0 = 4
    DEL = 8
    C1 = 16
    NONCHAR_FDD0 = 32
    NONCHAR_BMP_END = 64
    NONCHAR_SUPP = 128

    MASKS = {
      nil => 0,
      scalars: ILL_FORMED | SURROGATE,
      xml_characters: ILL_FORMED | SURROGATE | LEGACY_C0 | NONCHAR_BMP_END,
      assignables: 255,
    }.freeze

    PATTERNS = {
      scalars: /(?!)/.freeze,
      xml_characters: /[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/.freeze,
      assignables: Regexp.new("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\uFDD0-\uFDEF\uFFFE\uFFFF" +
        (1..16).map { |plane| [(plane << 16) + 0xFFFE, (plane << 16) + 0xFFFF].pack('U*') }.join + "]").freeze,
    }.freeze

    def self.mask(subset)
      unless nil.equal?(subset) || Symbol === subset
        raise ArgumentError, "invalid unicode_subset: #{subset.inspect}"
      end
      MASKS.fetch(subset) { raise ArgumentError, "invalid unicode_subset: #{subset.inspect}" }
    end

    def self.classify(cp)
      return ILL_FORMED unless (0..0x10FFFF).cover?(cp)
      return SURROGATE if (0xD800..0xDFFF).cover?(cp)
      return LEGACY_C0 if cp < 0x20 && cp != 9 && cp != 10 && cp != 13
      return DEL if cp == 0x7F
      return C1 if (0x80..0x9F).cover?(cp)
      return NONCHAR_FDD0 if (0xFDD0..0xFDEF).cover?(cp)
      return cp <= 0xFFFF ? NONCHAR_BMP_END : NONCHAR_SUPP if cp & 0xFFFF >= 0xFFFE
      0
    end

    # Returns [codepoint, byte offset]; nil codepoint denotes ill-formed UTF-8.
    # Callers transcode non-UTF-8 input before checking its repertoire.
    def self.violation(string, subset)
      forbidden = mask(subset)
      return if forbidden == 0

      if string.valid_encoding?
        match = PATTERNS.fetch(subset).match(string)
        [match[0].ord, match.pre_match.bytesize] if match
      else
        offset = 0
        string.each_char do |char|
          return [nil, offset] unless char.valid_encoding?
          return [char.ord, offset] unless classify(char.ord) & forbidden == 0
          offset += char.bytesize
        end
        nil
      end
    end

    def self.scrub(string, subset)
      return string if mask(subset) == 0
      string = string.scrub("\uFFFD") unless string.valid_encoding?
      pattern = PATTERNS.fetch(subset)
      pattern.match?(string) ? string.gsub(pattern, "\uFFFD") : string
    end
  end
end
