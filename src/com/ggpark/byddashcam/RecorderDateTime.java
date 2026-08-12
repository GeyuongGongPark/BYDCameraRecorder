package com.ggpark.byddashcam;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecorderDateTime {
    private static final String DIRECTORY_PATTERN = "yyyy-MM-dd'T'HH-mm-ss";
    private static final String METADATA_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    private static final Pattern SEGMENT_NAME_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2})(?:-\\d+)?");

    private RecorderDateTime() {
    }

    public static String formatDirectoryTimestamp(Date date) {
        return formatter(DIRECTORY_PATTERN, Locale.US).format(date);
    }

    public static String formatDisplayTimestamp(
            long timestampMillis,
            DisplayDateFormat displayFormat) {
        DisplayDateFormat safeFormat =
                displayFormat == null ? DisplayDateFormat.LOCAL_SHORT : displayFormat;
        return formatter(safeFormat.pattern, Locale.getDefault())
                .format(new Date(timestampMillis));
    }

    public static String formatMetadataTimestamp(Date date) {
        SimpleDateFormat formatter = formatter(METADATA_PATTERN, Locale.US);
        formatter.setTimeZone(TimeZone.getDefault());
        return formatter.format(date);
    }

    public static String formatSegmentName(
            String segmentName,
            DisplayDateFormat displayFormat) {
        Matcher matcher = SEGMENT_NAME_PATTERN.matcher(segmentName);
        if (!matcher.matches()) {
            return segmentName;
        }
        try {
            Date parsed = formatter(DIRECTORY_PATTERN, Locale.US)
                    .parse(matcher.group(1));
            return parsed == null
                    ? segmentName
                    : formatDisplayTimestamp(parsed.getTime(), displayFormat);
        } catch (ParseException exception) {
            return segmentName;
        }
    }

    public static boolean isSegmentName(String name) {
        return name != null && SEGMENT_NAME_PATTERN.matcher(name).matches();
    }

    private static SimpleDateFormat formatter(String pattern, Locale locale) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, locale);
        formatter.setLenient(false);
        return formatter;
    }
}
