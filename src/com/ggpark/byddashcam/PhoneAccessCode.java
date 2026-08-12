package com.ggpark.byddashcam;

import java.security.SecureRandom;

public final class PhoneAccessCode {
    private static final String ACCESS_ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int ACCESS_CODE_LENGTH = 8;
    private static final int PIN_LENGTH = 6;

    private PhoneAccessCode() {
    }

    public static String createAccessCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder(ACCESS_CODE_LENGTH);
        for (int index = 0; index < ACCESS_CODE_LENGTH; index++) {
            result.append(
                    ACCESS_ALPHABET.charAt(
                            random.nextInt(ACCESS_ALPHABET.length())));
        }
        return result.toString();
    }

    public static String createPin() {
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder(PIN_LENGTH);
        for (int index = 0; index < PIN_LENGTH; index++) {
            result.append(random.nextInt(10));
        }
        return result.toString();
    }
}
