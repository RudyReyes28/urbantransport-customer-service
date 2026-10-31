package com.urbantransport.customer.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class NitUtils {

    /** Formato canónico: dígitos con un último carácter que puede ser K (dígito verificador). */
    private static final Pattern VALID_NIT = Pattern.compile("^[0-9]+[0-9K]$");

    private NitUtils() {
    }

    /** Mayúsculas, sin espacios ni guiones: "1234567-8" pasa a "12345678". */
    public static String normalize(String raw) {
        return raw == null ? null : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String nit) {
        return nit != null && VALID_NIT.matcher(nit).matches();
    }
}