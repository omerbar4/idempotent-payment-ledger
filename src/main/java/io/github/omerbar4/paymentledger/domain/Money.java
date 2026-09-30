package io.github.omerbar4.paymentledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    public static final int SCALE = 4;

    private Money() {
    }

    /**
     * Normalizes an amount to the ledger scale. Uses UNNECESSARY rounding so that an amount
     * with more precision than the ledger supports fails loudly instead of being silently rounded.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
