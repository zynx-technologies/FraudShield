package com.fraudshield.decision.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One payment to decide. Card numbers and account identifiers arrive already tokenised by the bank's
 * tokenisation service, so this service stays out of PCI DSS cardholder-data scope.
 */
public record DecisionRequest(
        @NotBlank @Size(max = 64) String transactionId,
        @NotNull Channel channel,
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 64) String accountToken,
        @Size(max = 64) String payeeToken,
        @Size(max = 64) String deviceId,
        @Size(max = 40) String merchantCategory,
        @Valid Session session) {

    /** Device and session signals sent by the banking app with each request (design section 6). */
    public record Session(Boolean remoteAccess, Boolean newLocation) {}

    public boolean remoteAccess() { return session != null && Boolean.TRUE.equals(session.remoteAccess()); }

    public boolean newLocation() { return session != null && Boolean.TRUE.equals(session.newLocation()); }
}