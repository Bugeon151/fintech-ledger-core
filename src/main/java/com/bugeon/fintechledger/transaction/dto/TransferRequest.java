package com.bugeon.fintechledger.transaction.dto;

import com.bugeon.fintechledger.transaction.domain.MoneyCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(

        @NotNull(message = "From account ID is required")
        UUID fromAccountId,

        @NotNull(message = "To account ID is required")
        UUID toAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "Amount supports at most 17 integer and 2 fraction digits")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "(?i)KRW|USD|EUR", message = "Currency must be KRW, USD, or EUR")
        String currency,

        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description
) {
    public MoneyCommand toCommand() {
        return MoneyCommand.transfer(fromAccountId, toAccountId, amount, currency, description);
    }
}
