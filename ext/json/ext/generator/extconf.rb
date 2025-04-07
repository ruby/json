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

          if enable_config('generator-use-neon-lut', default=false)
            $defs.push('-DUSE_NEON_LUT')
          end
      end
    end

    if have_header('x86intrin.h') && have_type('__m128i', headers=['x86intrin.h']) && try_compile(<<~'SRC', opt='-msse2')
      #include <x86intrin.h>
      int main() {
          __m128i test = _mm_set1_epi8(32);
          return 0;
      }
      SRC
        $defs.push("-DENABLE_SIMD")
    end
    
    have_header('cpuid.h')
  end

  create_header

  create_makefile 'json/ext/generator'
end
