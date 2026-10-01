package com.codewus.showcase.order.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record OrderCommand(String actorId, String actionId, String symbol, Side side, BigDecimal quantity) {
    public enum Side { BUY, SELL }

    public OrderCommand {
        requireIdentifier(actorId, "actorId");
        requireIdentifier(actionId, "actionId");
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9]{1,19}")) {
            throw new IllegalArgumentException("symbol must be 2-20 uppercase letters/digits");
        }
        if (side == null || quantity == null || quantity.signum() <= 0
                || quantity.stripTrailingZeros().scale() > 8 || (long) quantity.precision() - quantity.scale() > 12) {
            throw new IllegalArgumentException("positive quantity with at most 12 integer and 8 decimal digits required");
        }
        quantity = quantity.stripTrailingZeros();
    }

    public static void requireIdentifier(String value, String name) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(name + " must be 1-64 letters/digits/underscore/hyphen");
        }
    }

    public String clientOrderId() {
        return "demo-" + hash(actorId + "|" + actionId).substring(0, 30);
    }

    public String fingerprint() {
        return hash(actorId + "|" + actionId + "|" + symbol + "|" + side + "|" + quantity.toPlainString());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
