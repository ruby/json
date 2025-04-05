#include "extconf.h"

typedef enum {
    SIMD_NONE,
    SIMD_NEON,
    SIMD_SSE2
} SIMD_Implementation;

#ifdef ENABLE_SIMD

#if defined(__ARM_NEON) || defined(__ARM_NEON__) || defined(__aarch64__) || defined(_M_ARM64)
#include <arm_neon.h>

#define FIND_SIMD_IMPLEMENTATION_DEFINED 1
SIMD_Implementation find_simd_implementation() {
    return SIMD_NEON;
}

#define HAVE_SIMD_NEON 1

uint8x16x4_t load_uint8x16_4(const unsigned char *table) {
  uint8x16x4_t tab;
  tab.val[0] = vld1q_u8(table);
  tab.val[1] = vld1q_u8(table+16);
  tab.val[2] = vld1q_u8(table+32);
  tab.val[3] = vld1q_u8(table+48);
  return tab;
}

void print_uint8x16(char *msg, uint8x16_t vec) {
  printf("%s\n[ ", msg);
  uint8_t store[16] = {0};
  vst1q_u8(store, vec);
  for(int i=0; i<16; i++) {
    printf("%3d ", store[i]);
  }
  printf("]\n");
}

#endif /* ARM Neon Support.*/

#if defined(__amd64__) || defined(__amd64) || defined(__x86_64__) || defined(__x86_64) || defined(_M_X64) || defined(_M_AMD64)

#ifdef HAVE_X86INTRIN_H
#include <x86intrin.h>

#define HAVE_SIMD_SSE2 1

void print_m128i(const char *prefix, __m128i vec) {
    uint8_t r[16];
    _mm_storeu_si128((__m128i *) r, vec);

    printf("%s = [ ", prefix);
    for(int i=0; i<16; i++) {
        printf("%02x ", r[i]);
    }
    printf("]\n");
}

#ifdef HAVE_CPUID_H
#define FIND_SIMD_IMPLEMENTATION_DEFINED 1

#include <cpuid.h>
#endif /* HAVE_CPUID_H */

SIMD_Implementation find_simd_implementation(void) {

#if defined(__GNUC__ ) || defined(__clang__)
#ifdef __GNUC__ 
    __builtin_cpu_init();
#endif /* __GNUC__  */

    // TODO Revisit. I think the SSE version now only uses SSE2 instructions.
    if (__builtin_cpu_supports("sse2")) {
        return SIMD_SSE2;
    }
#endif /* __GNUC__ || __clang__*/

    return SIMD_NONE;
}

#endif /* HAVE_X86INTRIN_H */
#endif /* X86_64 Support */

#endif /* ENABLE_SIMD */

#ifndef FIND_SIMD_IMPLEMENTATION_DEFINED
SIMD_Implementation find_simd_implementation(void) {
    return SIMD_NONE;
}
#endif