package com.ggpark.byddashcam;

public final class PhoneJson {
    private PhoneJson() {
    }

    public static boolean booleanValue(
            String json,
            String key,
            boolean fallback) {
        String value = rawValue(json, key);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        return fallback;
    }

    public static double doubleValue(
            String json,
            String key,
            double fallback) {
        try {
            String value = rawValue(json, key);
            return value == null ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static int intValue(String json, String key, int fallback) {
        try {
            String value = rawValue(json, key);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static String stringValue(
            String json,
            String key,
            String fallback) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        int separator = keyIndex < 0
                ? -1
                : json.indexOf(':', keyIndex + marker.length());
        if (separator < 0) {
            return fallback;
        }
        int start = json.indexOf('"', separator + 1);
        if (start < 0) {
            return fallback;
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = start + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaped) {
                value.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return value.toString();
            } else {
                value.append(character);
            }
        }
        return fallback;
    }

    public static String quote(String value) {
        String safe = value == null ? "" : value;
        StringBuilder result = new StringBuilder(safe.length() + 2);
        result.append('"');
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            switch (character) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '"':
                    result.append("\\\"");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(' ');
                    } else {
                        result.append(character);
                    }
                    break;
            }
        }
        result.append('"');
        return result.toString();
    }

    private static String rawValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        int separator = keyIndex < 0
                ? -1
                : json.indexOf(':', keyIndex + marker.length());
        if (separator < 0) {
            return null;
        }
        int start = separator + 1;
        while (start < json.length()
                && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length()) {
            char character = json.charAt(end);
            if (character == ',' || character == '}' || Character.isWhitespace(character)) {
                break;
            }
            end++;
        }
        return start == end ? null : json.substring(start, end);
    }
}
