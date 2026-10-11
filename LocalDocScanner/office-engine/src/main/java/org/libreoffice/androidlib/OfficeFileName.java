// SPDX-License-Identifier: MIT
package org.libreoffice.androidlib;

import java.util.Locale;

/** Provider metadata is optional; names used by copy/export must always be non-empty. */
public final class OfficeFileName {
    private OfficeFileName() {}

    public static String displayName(String candidate, String fallback, boolean withExtension) {
        String name = candidate == null ? "" : candidate.trim();
        if (name.isEmpty()) name = fallback == null ? "" : fallback.trim();
        if (name.isEmpty()) name = "文档";
        int dot = name.lastIndexOf('.');
        return !withExtension && dot > 0 ? name.substring(0, dot) : name;
    }

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
