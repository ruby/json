#include <stdint.h>
    
typedef uint8_t uint8;
typedef uint32_t uint32;
#define Assert(_) (void)0

#include "../vendor/postgres/src/include/port/simd.h"
// https://github.com/postgres/postgres/blob/REL_17_4/src/include/port/simd.h