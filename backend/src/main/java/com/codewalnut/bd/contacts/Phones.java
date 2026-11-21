package com.codewalnut.bd.contacts;

/** WhatsApp numbers kept as digits with the country code, so click-to-chat links and de-duplication work. */
public final class Phones {

    private Phones() {
    }

    /**
     * The number as 10–15 digits with the country code, or null when it isn't one. A 10-digit number is taken
     * to be Indian (+91), the team's usual case; a leading 0 is dropped.
     */
    public static String whatsapp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            digits = "91" + digits;
        }
        return digits.length() >= 11 && digits.length() <= 15 ? digits : null;
    }
}
