# frozen_string_literal: true
unless defined?(::JSON::JSON_LOADED) and ::JSON::JSON_LOADED
  require 'json'
end

# == Binary \String As \JSON
#
# Here is a strategy for converting a binary string to \JSON,
# and converting the \JSON back to a string.
#
# Construct a binary string with ASCII-8BIT encoding.
#
#   binary_s = ''.b                  # Empty binary string.
#   (0..3).each {|i| binary_s << i } # Populate the string.
#   binary_s                         # => "\x00\x01\x02\x03"
#   binary_s.encoding                # => #<Encoding:BINARY (ASCII-8BIT)>
#
# Convert the binary string to a specially-formatted \JSON string with UTF-8 encoding:
#
#   json_s = binary_s.to_json_raw    # => "{\"json_class\":\"String\",\"raw\":[0,1,2,3]}"
#   json_s.encoding                  # => #<Encoding:UTF-8>
#
# Convert the specially-formatted \JSON string to a hash,
# then to a binary string with ASCII-8BIT encoding:
#
#   hash = JSON.parse(json_s)           # => {"json_class" => "String", "raw" => [0, 1, 2, 3]}
#   binary_s = String.json_create(hash) # => "\x00\x01\x02\x03"
#   binary_s.encoding                   # => #<Encoding:BINARY (ASCII-8BIT)>
#
class String
  # call-seq:
  #   self.json_create(hash) -> new_string
  #
  # Returns a binary string with ASCII-8BIT encoding, converted from a specially formatted hash;
  # see {Binary String As JSON}[rdoc-ref:String@Binary+String+As+JSON].
  def self.json_create(object)
    object["raw"].pack("C*")
  end

    def to_json_raw_object # :nodoc:
    {
      JSON.create_id => self.class.name,
      "raw" => unpack("C*"),
    }
  end

  # call-seq:
  #   to_json_raw -> new_string
  #
  # Returns a specially formatted \JSON string, constructed from +self+;
  # see {Binary String As JSON}[rdoc-ref:String@Binary+String+As+JSON].
  def to_json_raw(...)
    to_json_raw_object.to_json(...)
  end
end
