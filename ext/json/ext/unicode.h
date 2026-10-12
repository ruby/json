#ifndef JSON_UNICODE_H
#define JSON_UNICODE_H

enum {
    JSON_UNICODE_ILL_FORMED = 1 << 0,
    JSON_UNICODE_SURROGATE = 1 << 1,
    JSON_UNICODE_LEGACY_C0 = 1 << 2,
    JSON_UNICODE_DEL = 1 << 3,
    JSON_UNICODE_C1 = 1 << 4,
    JSON_UNICODE_NONCHAR_FDD0 = 1 << 5,
    JSON_UNICODE_NONCHAR_BMP_END = 1 << 6,
    JSON_UNICODE_NONCHAR_SUPP = 1 << 7,
    JSON_UNICODE_SCALARS = JSON_UNICODE_ILL_FORMED | JSON_UNICODE_SURROGATE,
    JSON_UNICODE_XML_CHARACTERS = JSON_UNICODE_SCALARS | JSON_UNICODE_LEGACY_C0 | JSON_UNICODE_NONCHAR_BMP_END,
    JSON_UNICODE_ASSIGNABLES = 0xFF,
};

static const uint8_t json_unicode_ascii_classes[256] = {
    4, 4, 4, 4, 4, 4, 4, 4, 4, 0, 0, 4, 4, 0, 4, 4,
    4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8,
};

static inline uint8_t json_unicode_classify(uint32_t cp)
{
    if (cp < 0x80) return json_unicode_ascii_classes[cp];
    if (cp > 0x10FFFF) return JSON_UNICODE_ILL_FORMED;
    if (cp >= 0xD800 && cp <= 0xDFFF) return JSON_UNICODE_SURROGATE;
    if (cp >= 0x80 && cp <= 0x9F) return JSON_UNICODE_C1;
    if (cp >= 0xFDD0 && cp <= 0xFDEF) return JSON_UNICODE_NONCHAR_FDD0;
    if ((cp & 0xFFFF) >= 0xFFFE) {
        return cp <= 0xFFFF ? JSON_UNICODE_NONCHAR_BMP_END : JSON_UNICODE_NONCHAR_SUPP;
    }
    return 0;
}

/* Consume a maximal subpart, as defined by Unicode's U+FFFD substitution rules. */
static inline size_t json_unicode_decode(const char *ptr, const char *end, uint32_t *cp)
{
    const unsigned char *p = (const unsigned char *)ptr;
    unsigned char first = p[0];
    size_t length;
    unsigned char lower = 0x80, upper = 0xBF;
    uint32_t value;
    if (first < 0x80) {
        *cp = first;
        return 1;
    }
    *cp = UINT32_MAX;
    if (first >= 0xC2 && first <= 0xDF) {
        length = 2;
        value = first & 0x1F;
    } else if (first >= 0xE0 && first <= 0xEF) {
        length = 3;
        value = first & 0x0F;
        if (first == 0xE0) lower = 0xA0;
        if (first == 0xED) upper = 0x9F;
    } else if (first >= 0xF0 && first <= 0xF4) {
        length = 4;
        value = first & 0x07;
        if (first == 0xF0) lower = 0x90;
        if (first == 0xF4) upper = 0x8F;
    } else {
        return 1;
    }
    for (size_t i = 1; i < length; i++) {
        if ((size_t)(end - ptr) <= i || p[i] < lower || p[i] > upper) return i;
        value = (value << 6) | (p[i] & 0x3F);
        lower = 0x80;
        upper = 0xBF;
    }
    *cp = value;
    return length;
}

#ifdef HAVE_SIMD_SSE2
static inline TARGET_SSE2
#else
static inline
#endif
const char *json_unicode_scan(const char *p, const char *end, uint8_t mask, uint32_t *cp, size_t *length)
{
    while (p < end) {
        if ((unsigned char)*p >= 0x20 && (unsigned char)*p < 0x7F) {
#ifdef HAVE_SIMD_NEON
            while ((size_t)(end - p) >= sizeof(uint8x16_t)) {
                uint8x16_t bytes = vld1q_u8((const unsigned char *)p);
                uint8x16_t matches = vorrq_u8(vcltq_u8(bytes, vdupq_n_u8(0x20)), vcgeq_u8(bytes, vdupq_n_u8(0x7F)));
                uint64_t non_ascii = neon_match_mask(matches);
                if (non_ascii) {
                    p += trailing_zeros64(non_ascii) >> 2;
                    break;
                }
                p += sizeof(bytes);
            }
#elif defined(HAVE_SIMD_SSE2)
            while ((size_t)(end - p) >= sizeof(__m128i)) {
                __m128i bytes = _mm_loadu_si128((const __m128i *)p);
                __m128i matches = _mm_or_si128(_mm_cmplt_epu8(bytes, _mm_set1_epi8(0x20)), _mm_cmpgt_epu8(bytes, _mm_set1_epi8(0x7E)));
                int non_ascii = _mm_movemask_epi8(matches);
                if (non_ascii) {
                    p += trailing_zeros(non_ascii);
                    break;
                }
                p += sizeof(bytes);
            }
#endif
            while ((size_t)(end - p) >= sizeof(uint64_t)) {
                uint64_t bytes;
                memcpy(&bytes, p, sizeof(bytes));
                if ((bytes & UINT64_C(0x8080808080808080)) ||
                    ((bytes - UINT64_C(0x2020202020202020)) & UINT64_C(0x8080808080808080)) ||
                    ((bytes + UINT64_C(0x0101010101010101)) & UINT64_C(0x8080808080808080))) break;
                p += sizeof(bytes);
            }
            while (p < end && (unsigned char)*p >= 0x20 && (unsigned char)*p < 0x7F) {
                p++;
            }
            if (p == end) break;
        }
        /* U+1000..CFFF are allowed by every repertoire. */
        while ((size_t)(end - p) >= 3 &&
                (unsigned char)p[0] >= 0xE1 && (unsigned char)p[0] <= 0xEC &&
                ((unsigned char)p[1] & 0xC0) == 0x80 &&
                ((unsigned char)p[2] & 0xC0) == 0x80) {
            p += 3;
        }
        if (p == end) break;
        *length = json_unicode_decode(p, end, cp);
        if (json_unicode_classify(*cp) & mask) return p;
        p += *length;
    }
    return NULL;
}

static inline uint8_t json_unicode_subset_mask(VALUE value)
{
    if (NIL_P(value)) return 0;
    if (value == ID2SYM(rb_intern("scalars"))) return JSON_UNICODE_SCALARS;
    if (value == ID2SYM(rb_intern("xml_characters"))) return JSON_UNICODE_XML_CHARACTERS;
    if (value == ID2SYM(rb_intern("assignables"))) return JSON_UNICODE_ASSIGNABLES;
    rb_raise(rb_eArgError, "unicode_subset must be nil, :scalars, :xml_characters, or :assignables");
}

static inline const char *json_unicode_subset_name(uint8_t mask)
{
    switch (mask) {
        case JSON_UNICODE_SCALARS: return "scalars";
        case JSON_UNICODE_XML_CHARACTERS: return "xml_characters";
        case JSON_UNICODE_ASSIGNABLES: return "assignables";
        default: return NULL;
    }
}

static inline VALUE json_unicode_subset_value(uint8_t mask)
{
    return mask ? ID2SYM(rb_intern(json_unicode_subset_name(mask))) : Qnil;
}

static inline bool json_unicode_replace(VALUE value)
{
    if (value == ID2SYM(rb_intern("raise"))) return false;
    if (value == ID2SYM(rb_intern("replace"))) return true;
    rb_raise(rb_eArgError, "on_invalid_char must be :raise or :replace");
}

#endif
