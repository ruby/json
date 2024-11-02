#ifndef _NAME_CACHE_H_
#define _NAME_CACHE_H_

#include "ruby.h"
#include <string.h>

#ifndef RB_UNLIKELY
#define RB_UNLIKELY(expr) expr
#endif

#define JSON_RSTRING_CACHE_CAPA 63
#define JSON_RSTRING_CACHE_MAX_ENTRY_LENGTH 55

typedef struct rstring_cache_struct {
    int length;
    VALUE entries[JSON_RSTRING_CACHE_CAPA];
} rstring_cache;

static inline int rstring_cache_cmp(const char *str, const long length, VALUE rstring)
{
    long rstring_length = RSTRING_LEN(rstring);
    if (length == rstring_length) {
        return memcmp(str, RSTRING_PTR(rstring), length);
    } else {
        return (int)(length - rstring_length);
    }
}

static void rstring_cache_insert_at(rstring_cache *cache, int index, VALUE rstring)
{
    MEMMOVE(&cache->entries[index + 1], &cache->entries[index], VALUE, cache->length - index);
    cache->length ++;
    cache->entries[index] = rstring;
}

static VALUE rstring_cache_fetch(rstring_cache *cache, const char *str, const long length)
{
    if (RB_UNLIKELY(length > JSON_RSTRING_CACHE_MAX_ENTRY_LENGTH)) {
        rb_enc_interned_str(str, length, rb_utf8_encoding());
    }

    int low = 0;
    int high = cache->length - 1;
    int mid = 0;
    int last_cmp = 0;
    if (cache->length) {
        while (low <= high) {
            mid = (high + low) / 2;
            VALUE entry = cache->entries[mid];
            last_cmp = rstring_cache_cmp(str, length, entry);

            if (last_cmp == 0) {
                return entry;
            } else if (last_cmp > 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
    }

    VALUE rstring = rb_enc_interned_str(str, length, rb_utf8_encoding());

    if (cache->length < JSON_RSTRING_CACHE_CAPA) {
        if (last_cmp > 0) {
            mid += 1;
        }

        rstring_cache_insert_at(cache, mid, rstring);
    }
    return rstring;
}

#endif
