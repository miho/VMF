/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.mihosoft.vmf.core.codegen;

import eu.mihosoft.vmf.core.codegen.ResolvedModel.CollectionDefault;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ScalarDefault;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Decodes the Java 11 literal subset of a default without evaluating a Java expression. */
final class JavaScalarDefaults {
    private static final String DIGITS = "[0-9](?:[0-9_]*[0-9])?";
    private static final String HEX_DIGITS = "[0-9a-fA-F](?:[0-9a-fA-F_]*[0-9a-fA-F])?";
    private static final String EXPONENT = "[eE][+-]?" + DIGITS;
    private static final Pattern DECIMAL_FLOAT = Pattern.compile("(?:(?:" + DIGITS + "\\.(?:" + DIGITS
            + ")?|\\." + DIGITS + ")(?:" + EXPONENT + ")?[fFdD]?|" + DIGITS + EXPONENT + "[fFdD]?|" + DIGITS + "[fFdD])");
    private static final Pattern HEX_FLOAT = Pattern.compile("0[xX](?:" + HEX_DIGITS + "\\.?|(?:" + HEX_DIGITS
            + ")?\\." + HEX_DIGITS + ")[pP][+-]?" + DIGITS + "[fFdD]?");
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9](?:[0-9_]*[0-9])?");
    private static final Pattern HEX = Pattern.compile("0[xX]" + HEX_DIGITS);
    private static final Pattern BINARY = Pattern.compile("0[bB][01](?:[01_]*[01])?");
    private static final Pattern OCTAL = Pattern.compile("0[0-7_]*[0-7]");

    private JavaScalarDefaults() {
    }

    static ScalarDefault parse(String source, ValueType type) {
        // VMF's Java templates treat exactly this spelling (including an empty annotation
        // normalized by Prop) as absence of an initializer, even on primitive properties.
        if (source.equals("null")) {
            Object value = null;
            if (type.isPrimitive()) {
                value = type.getKind() == ValueType.Kind.BOOLEAN ? Boolean.FALSE : convertNumber(0, false, type);
            }
            return new ScalarDefault(type.getKind(), value);
        }
        try {
            String text = stripJavaWhitespace(translateUnicodeEscapes(source));
            if (text.equals("null") && !type.isPrimitive()) return new ScalarDefault(type.getKind(), null);
            Object value = parseLiteral(text, type);
            return value == null ? null : new ScalarDefault(type.getKind(), value);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    static CollectionDefault parseCollection(String source, ValueType valueType, ValueType keyType, boolean ordered) {
        try {
            boolean map = keyType != null;
            String factory = map ? "java.util.Map.of" : ordered ? "java.util.List.of" : "java.util.Set.of";
            String text = stripJavaWhitespace(translateUnicodeEscapes(source));
            if (!text.startsWith(factory + "(") || !text.endsWith(")")) return null;
            List<String> arguments = splitArguments(text.substring(factory.length() + 1, text.length() - 1));
            if (map && arguments.size() != 0 && arguments.size() != 2) return null;
            if (!map && !ordered && arguments.size() > 1) return null;
            var keys = new ArrayList<ScalarDefault>();
            var values = new ArrayList<ScalarDefault>();
            for (int index = 0; index < arguments.size(); index++) {
                boolean key = map && index == 0;
                ValueType type = key ? keyType : valueType;
                String literal = arguments.get(index);
                if (type.getKind() == ValueType.Kind.INT8 || type.getKind() == ValueType.Kind.INT16
                        || type.getKind() == ValueType.Kind.CHAR && !literal.startsWith("'")) return null;
                Object value = parseLiteral(literal, type);
                if (value == null) return null;
                (key ? keys : values).add(new ScalarDefault(type.getKind(), value));
            }
            return new CollectionDefault(keys, values);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static List<String> splitArguments(String source) {
        if (stripJavaWhitespace(source).isEmpty()) return List.of();
        List<String> values = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (current == '\\') index++;
                else if (current == quote) quote = 0;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == ',') {
                values.add(stripJavaWhitespace(source.substring(start, index)));
                start = index + 1;
            }
        }
        values.add(stripJavaWhitespace(source.substring(start)));
        return values;
    }

    private static Object parseLiteral(String text, ValueType type) {
        if (text.isEmpty()) return null;
        switch (type.getKind()) {
            case BOOLEAN:
                return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
            case STRING:
                return decodeQuoted(text, '"');
            default:
                if (text.charAt(0) == '\'') {
                    String character = decodeQuoted(text, '\'');
                    return character.length() == 1 ? convertNumber((int) character.charAt(0), true, type) : null;
                }
                var number = parseNumber(text);
                return number == null ? null : convertNumber(number, false, type);
        }
    }

    private static Number parseNumber(String text) {
        boolean negative = text.charAt(0) == '-';
        String literal = negative || text.charAt(0) == '+' ? stripJavaWhitespace(text.substring(1)) : text;
        if (literal.isEmpty()) return null;
        boolean hexadecimal = HEX_FLOAT.matcher(literal).matches();
        if (hexadecimal || DECIMAL_FLOAT.matcher(literal).matches()) {
            return parseFloatingPoint(literal, negative, hexadecimal);
        }

        boolean wide = hasSuffix(literal, 'l', 'L');
        if (wide) literal = literal.substring(0, literal.length() - 1);
        if (DECIMAL.matcher(literal).matches()) return parseInteger(literal, 10, negative, wide);
        if (HEX.matcher(literal).matches()) return parseInteger(literal.substring(2), 16, negative, wide);
        if (BINARY.matcher(literal).matches()) return parseInteger(literal.substring(2), 2, negative, wide);
        if (OCTAL.matcher(literal).matches()) return parseInteger(literal, 8, negative, wide);
        return null;
    }

    private static Number parseInteger(String digits, int base, boolean negative, boolean wide) {
        var magnitude = new BigInteger(digits.replace("_", ""), base);
        int width = wide ? Long.SIZE : Integer.SIZE;
        if (base == 10) {
            var limit = BigInteger.ONE.shiftLeft(width - 1);
            int comparison = magnitude.compareTo(limit);
            if (comparison > 0 || (comparison == 0 && !negative)) return null;
        } else if (magnitude.bitLength() > width) {
            return null;
        }
        if (wide) return negative ? -magnitude.longValue() : magnitude.longValue();
        return negative ? -magnitude.intValue() : magnitude.intValue();
    }

    private static Number parseFloatingPoint(String literal, boolean negative, boolean hexadecimal) {
        String signed = (negative ? "-" : "") + literal.replace("_", "");
        if (hasSuffix(literal, 'f', 'F')) {
            float value = Float.parseFloat(signed);
            return Float.isFinite(value) && (value != 0.0f || !hasNonzeroSignificand(literal, hexadecimal)) ? value : null;
        }
        double value = Double.parseDouble(signed);
        return Double.isFinite(value) && (value != 0.0 || !hasNonzeroSignificand(literal, hexadecimal)) ? value : null;
    }

    private static boolean hasNonzeroSignificand(String literal, boolean hexadecimal) {
        int base = hexadecimal ? 16 : 10;
        for (int i = hexadecimal ? 2 : 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (hexadecimal ? c == 'p' || c == 'P' : c == 'e' || c == 'E' || c == 'f' || c == 'F' || c == 'd' || c == 'D') {
                break;
            }
            if (asciiHexDigit(c) > 0 && asciiHexDigit(c) < base) return true;
        }
        return false;
    }

    private static Object convertNumber(Number value, boolean charLiteral, ValueType type) {
        boolean intLiteral = value instanceof Integer;
        boolean integerLiteral = intLiteral || value instanceof Long;
        boolean primitive = type.isPrimitive();
        long integral = value.longValue();
        switch (type.getKind()) {
            case INT8:
                return intLiteral && integral >= Byte.MIN_VALUE && integral <= Byte.MAX_VALUE ? value.byteValue() : null;
            case INT16:
                return intLiteral && integral >= Short.MIN_VALUE && integral <= Short.MAX_VALUE ? value.shortValue() : null;
            case CHAR:
                return intLiteral && integral >= Character.MIN_VALUE && integral <= Character.MAX_VALUE ? (char) integral : null;
            case INT32:
                return intLiteral && (!charLiteral || primitive) ? value.intValue() : null;
            case INT64:
                return value instanceof Long || (primitive && intLiteral) ? integral : null;
            case FLOAT32:
                return value instanceof Float || (primitive && integerLiteral) ? value.floatValue() : null;
            case FLOAT64:
                return value instanceof Double || primitive ? value.doubleValue() : null;
            default:
                return null;
        }
    }

    private static boolean hasSuffix(String literal, char lower, char upper) {
        char end = literal.charAt(literal.length() - 1);
        return end == lower || end == upper;
    }

    private static String stripJavaWhitespace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isJavaWhitespace(text.charAt(start))) start++;
        while (end > start && isJavaWhitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean isJavaWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r';
    }

    private static String translateUnicodeEscapes(String source) {
        if (source.indexOf('\\') < 0) return source;
        var result = new StringBuilder(source.length());
        int slashes = 0;
        boolean previousEscape = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean startsEscape = c == '\\' && (previousEscape || slashes % 2 == 0)
                    && i + 1 < source.length() && source.charAt(i + 1) == 'u';
            if (startsEscape) {
                int start = i + 2;
                while (start < source.length() && source.charAt(start) == 'u') start++;
                if (start + 4 > source.length()) throw new IllegalArgumentException("Incomplete Unicode escape");
                int value = 0;
                for (int j = start; j < start + 4; j++) {
                    int digit = asciiHexDigit(source.charAt(j));
                    if (digit < 0) throw new IllegalArgumentException("Invalid Unicode escape");
                    value = value * 16 + digit;
                }
                c = (char) value;
                i = start + 3;
            }
            result.append(c);
            slashes = c == '\\' ? slashes + 1 : 0;
            previousEscape = startsEscape;
        }
        return result.toString();
    }

    private static int asciiHexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String decodeQuoted(String literal, char quote) {
        int end = literal.length() - 1;
        if (end < 1 || literal.charAt(0) != quote || literal.charAt(end) != quote) {
            throw new IllegalArgumentException("Expected a quoted Java literal");
        }
        var decoded = new StringBuilder(end - 1);
        for (int i = 1; i < end; i++) {
            char c = literal.charAt(i);
            if (c == quote || c == '\r' || c == '\n') throw new IllegalArgumentException("Invalid Java literal character");
            if (c != '\\') {
                decoded.append(c);
                continue;
            }
            if (++i >= end) throw new IllegalArgumentException("Incomplete Java escape");
            char escape = literal.charAt(i);
            if (escape >= '0' && escape <= '7') {
                // Three octal digits are legal only when the first digit is 0 through 3.
                int limit = Math.min(end, i + (escape <= '3' ? 3 : 2));
                int value = escape - '0';
                while (i + 1 < limit && literal.charAt(i + 1) >= '0' && literal.charAt(i + 1) <= '7') {
                    value = value * 8 + literal.charAt(++i) - '0';
                }
                decoded.append((char) value);
            } else {
                decoded.append(decodeEscape(escape));
            }
        }
        return decoded.toString();
    }

    private static char decodeEscape(char c) {
        switch (c) {
            case 'b': return '\b';
            case 't': return '\t';
            case 'n': return '\n';
            case 'f': return '\f';
            case 'r': return '\r';
            case '\'':
            case '"':
            case '\\': return c;
            default: throw new IllegalArgumentException("Unsupported Java escape");
        }
    }
}
