package com.ggpark.byddashcam;

public enum DisplayDateFormat {
    LOCAL_SHORT("local_short", "Local: 26/07/2026 17:42", "dd/MM/yyyy HH:mm"),
    ISO("iso", "ISO: 2026-07-26 17:42", "yyyy-MM-dd HH:mm"),
    MONTH_FIRST("month_first", "US: 07/26/2026 5:42 PM", "MM/dd/yyyy h:mm a"),
    DAY_MONTH_NAME("day_month_name", "26 Jul 2026, 17:42", "dd MMM yyyy, HH:mm");

    public final String id;
    public final String label;
    public final String pattern;

    DisplayDateFormat(String id, String label, String pattern) {
        this.id = id;
        this.label = label;
        this.pattern = pattern;
    }

    public static DisplayDateFormat fromId(String id) {
        for (DisplayDateFormat format : values()) {
            if (format.id.equals(id)) {
                return format;
            }
        }
        return LOCAL_SHORT;
    }
}
