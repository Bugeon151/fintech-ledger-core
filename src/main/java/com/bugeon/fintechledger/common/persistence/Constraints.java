package com.bugeon.fintechledger.common.persistence;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;

/** Identifies which database constraint a persistence exception violated. */
public final class Constraints {

    public static final String TRANSACTION_IDEMPOTENCY_KEY = "uq_transactions_requester_idem_key";
    public static final String ACCOUNT_NUMBER              = "uq_accounts_account_number";

    private Constraints() {
    }

    public static boolean isViolation(Throwable error, String constraintName) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t instanceof SQLException && t.getMessage() != null && t.getMessage().contains(constraintName)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
