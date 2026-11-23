package vn.greenmart.integration.channel;

import java.util.regex.Pattern;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

/** Demo WEB/MARKET identifiers. Product.sku has its own contract. */
public final class ExternalSkus {
    public static final String FORMAT = "[A-Za-z0-9][A-Za-z0-9._:-]{0,59}";
    private static final Pattern VALID = Pattern.compile(FORMAT);
    private static final Pattern OUTER_SPACE = Pattern.compile("(?U)^\\s+|\\s+$");

    private ExternalSkus() {}

    public static String normalize(String sku) {
        return sku == null ? null : OUTER_SPACE.matcher(sku).replaceAll("");
    }

    public static String requireValid(String sku) {
        String normalized = normalize(sku);
        if (normalized == null || !VALID.matcher(normalized).matches()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    "SKU kênh phải có 1–60 ký tự ASCII, bắt đầu bằng chữ hoặc số, chỉ gồm chữ, số và ._:-");
        }
        return normalized;
    }
}
