package com.bugeon.fintechledger.ledger.repository;

import com.bugeon.fintechledger.ledger.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findAllByTransactionIdOrderByEntryTypeAsc(UUID transactionId);

    Page<LedgerEntry> findAllByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /** Authoritative balance of one account: SUM(CREDIT) - SUM(DEBIT). */
    @Query(value = """
            SELECT COALESCE(SUM(CASE entry_type WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries
            WHERE account_id = :accountId
            """, nativeQuery = true)
    BigDecimal calculateLedgerBalance(@Param("accountId") UUID accountId);

    /** Net of all legs per currency. Double-entry requires every value to be zero. */
    @Query(value = """
            SELECT currency AS currency,
                   SUM(CASE entry_type WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
            FROM ledger_entries
            GROUP BY currency
            """, nativeQuery = true)
    List<CurrencyNet> netByCurrency();

    interface CurrencyNet {
        String getCurrency();
        BigDecimal getNet();
    }
}
