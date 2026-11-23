package vn.greenmart.catalog.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Display normalization; identity/case comparison is owned by greenmart_category_key in PostgreSQL. */
public final class CategoryNames {
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern OUTER_SPACES = Pattern.compile("^ +| +$");

    private CategoryNames() {}

    public static String normalize(String name) {
        if (name == null) return null;
        String collapsed = WHITESPACE.matcher(Normalizer.normalize(name, Normalizer.Form.NFC)).replaceAll(" ");
        // Match PostgreSQL btrim(...): String.strip also removes non-White_Space U+001C–U+001F.
        return OUTER_SPACES.matcher(collapsed).replaceAll("");
    }
}
