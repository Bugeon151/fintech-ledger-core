package com.bugeon.fintechledger.transaction.domain;

import com.bugeon.fintechledger.common.domain.BaseEntity;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.common.json.Json;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Money-movement command aggregate.
 *
 * Lifecycle: a row is only ever committed as SUCCESS (inserted PENDING and completed in the
 * same database transaction as its ledger legs) or FAILED (recorded after the business
 * transaction rolled back). PROCESSING / CANCELED remain valid in the schema for legacy rows.
 *
 * (requested_by, idempotency_key) is the deduplication anchor.
 * request_payload (JSONB) stores the original command for auditability.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id", callSuper = false)
@ToString
public class Transaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Unique per requester (constraint uq_transactions_requester_idem_key). */
    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    /** 요청을 발행한 사용자. Idempotency-Key 결과 조회의 소유권 기준. */
    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "source_account_id", updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", updatable = false)
    private UUID targetAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private String requestPayload;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Factories ─────────────────────────────────────────────────────────────

    public static Transaction pending(String idempotencyKey, UUID requestedBy, MoneyCommand command) {
        Transaction tx = new Transaction();
        tx.idempotencyKey  = idempotencyKey;
        tx.requestedBy     = requestedBy;
        tx.type            = command.type();
        tx.status          = TransactionStatus.PENDING;
        tx.sourceAccountId = command.sourceAccountId();
        tx.targetAccountId = command.targetAccountId();
        tx.amount          = command.amount();
        tx.currency        = command.currency();
        tx.description     = command.description();
        tx.requestPayload  = Json.write(command);
        return tx;
    }

    /** A business rejection, persisted so that a retry with the same key replays the same error. */
    public static Transaction failed(String idempotencyKey, UUID requestedBy, MoneyCommand command,
                                     ErrorCode code, String reason) {
        Transaction tx = pending(idempotencyKey, requestedBy, command);
        tx.status        = TransactionStatus.FAILED;
        tx.failureCode   = code.name();
        tx.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        tx.completedAt   = Instant.now();
        return tx;
    }

    // ── Status transitions ────────────────────────────────────────────────────

    public void markSuccess() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Only a PENDING transaction can succeed, was " + status);
        }
        this.status      = TransactionStatus.SUCCESS;
        this.completedAt = Instant.now();
    }

    /** The original business error of a FAILED transaction, if it was recorded with one. */
    public Optional<ErrorCode> failureErrorCode() {
        if (status != TransactionStatus.FAILED || failureCode == null) {
            return Optional.empty();
        }
        return Arrays.stream(ErrorCode.values()).filter(c -> c.name().equals(failureCode)).findFirst();
    }
}
