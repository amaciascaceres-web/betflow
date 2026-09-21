package com.alejandromacias.betflow.wallet.funds;

/**
 * The sign each kind of entry contributes to the balance. {@code CONFIRMATION} is deliberately
 * neutral: the money left the available balance when it was reserved, and confirming only records
 * that the hold became a real spend.
 */
public enum LedgerEntryType {
    TOPUP,
    RESERVATION,
    CONFIRMATION,
    RELEASE
}
