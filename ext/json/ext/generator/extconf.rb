require 'mkmf'

if RUBY_ENGINE == 'truffleruby'
  # The pure-Ruby generator is faster on TruffleRuby, so skip compiling the generator extension
  File.write('Makefile', dummy_makefile("").join)
else
  append_cflags("-std=c99")
  $defs << "-DJSON_GENERATOR"

  if enable_config('generator-use-simd', default=true)
    if RbConfig::CONFIG['host_cpu'] =~ /^(arm.*|aarch64.*)/
      # Try to compile a small program using NEON instructions
      if have_header('arm_neon.h')
        have_type('uint8x16_t', headers=['arm_neon.h']) && try_compile(<<~'SRC')
          #include <arm_neon.h>
          int main() {
              uint8x16_t test = vdupq_n_u8(32);
              return 0;
          }
        SRC
          $defs.push("-DENABLE_SIMD")
      end
    end
  end

  create_header

  create_makefile 'json/ext/generator'
end
