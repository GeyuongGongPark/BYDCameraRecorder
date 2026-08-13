package com.ggpark.byddashcam;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

public final class LocaleHelper {
    private static final String PREF_NAME = "locale_pref";
    private static final String KEY_LANGUAGE = "language";
    private static final String DEFAULT_LANGUAGE = "ko";

    public static Context onAttach(Context context) {
        String lang = getPersistedLanguage(context, DEFAULT_LANGUAGE);
        return setLocale(context, lang);
    }

    public static String getLanguage(Context context) {
        return getPersistedLanguage(context, DEFAULT_LANGUAGE);
    }

    public static Context setLocale(Context context, String language) {
        persist(context, language);
        return updateResources(context, language);
    }

    private static Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    private static String getPersistedLanguage(Context context, String defaultLanguage) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, defaultLanguage);
    }

    private static void persist(Context context, String language) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, language).apply();
    }
}
