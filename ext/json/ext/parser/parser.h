#ifndef _PARSER_H_
#define _PARSER_H_

#include "ruby.h"

/* This is the fallback definition from Ruby 3.4 */
#ifndef RBIMPL_STDBOOL_H
#if defined(__cplusplus)
# if defined(HAVE_STDBOOL_H) && (__cplusplus >= 201103L)
#  include <cstdbool>
# endif
#elif defined(HAVE_STDBOOL_H)
# include <stdbool.h>
#elif !defined(HAVE__BOOL)
typedef unsigned char _Bool;
# define bool  _Bool
# define true  ((_Bool)+1)
# define false ((_Bool)+0)
# define __bool_true_false_are_defined
#endif
#endif

#ifndef MAYBE_UNUSED
# define MAYBE_UNUSED(x) x
#endif

// Object names are likely to be repeated, and are frozen.
// As such we can re-use them if we keep a cache of the ones we've seen so far,
// and save much more expensive lookups into the global fstring table.
// This cache implementation is deliberately simple, as we're optimizing for compactness,
// to be able to fit safely on the stack.
// As such, binary search into a sorted array gives a good tradeoff between compactness and
// performance.
#define JSON_RVALUE_CACHE_CAPA 63
typedef struct rvalue_cache_struct {
    int length;
    VALUE entries[JSON_RVALUE_CACHE_CAPA];
} rvalue_cache;

typedef struct JSON_ParserStruct {
    VALUE Vsource;
    char *source;
    long len;
    char *memo;
    VALUE create_id;
    VALUE object_class;
    VALUE array_class;
    VALUE decimal_class;
    VALUE match_string;
    FBuffer fbuffer;
    int max_nesting;
    bool allow_nan;
    bool parsing_name;
    bool symbolize_names;
    bool freeze;
    bool create_additions;
    bool deprecated_create_additions;
    rvalue_cache name_cache;
} JSON_Parser;

#define GET_PARSER                          \
    GET_PARSER_INIT;                        \
    if (!json->Vsource) rb_raise(rb_eTypeError, "uninitialized instance")
#define GET_PARSER_INIT                     \
    JSON_Parser *json;                      \
    TypedData_Get_Struct(self, JSON_Parser, &JSON_Parser_type, json)

#define MinusInfinity "-Infinity"
#define EVIL 0x666

static uint32_t unescape_unicode(const unsigned char *p);
static int convert_UTF32_to_UTF8(char *buf, uint32_t ch);
static char *JSON_parse_object(JSON_Parser *json, char *p, char *pe, VALUE *result, int current_nesting);
static char *JSON_parse_value(JSON_Parser *json, char *p, char *pe, VALUE *result, int current_nesting);
static char *JSON_parse_integer(JSON_Parser *json, char *p, char *pe, VALUE *result);
static char *JSON_parse_float(JSON_Parser *json, char *p, char *pe, VALUE *result);
static char *JSON_parse_array(JSON_Parser *json, char *p, char *pe, VALUE *result, int current_nesting);
static VALUE json_string_unescape(JSON_Parser *json, char *string, char *stringEnd, bool is_name, bool intern, bool symbolize);
static char *JSON_parse_string(JSON_Parser *json, char *p, char *pe, VALUE *result);
static VALUE convert_encoding(VALUE source);
static VALUE cParser_initialize(int argc, VALUE *argv, VALUE self);
static VALUE cParser_parse(VALUE self);
static void JSON_mark(void *json);
static void JSON_free(void *json);
static VALUE cJSON_parser_s_allocate(VALUE klass);
static VALUE cParser_source(VALUE self);

static const rb_data_type_t JSON_Parser_type;

#endif
