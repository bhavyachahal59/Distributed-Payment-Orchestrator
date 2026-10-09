
package dev.portfolio.payments.service;

import dev.portfolio.payments.api.PaymentResponse;
import dev.portfolio.payments.model.*;
import dev.portfolio.payments.provider.ProviderOutcome;
import dev.portfolio.payments.repo.PaymentEventRepository;
import dev.portfolio.payments.repo.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentProcessingTransactions {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final long retryMs;
    private final long reconciliationMs;
    private final long processingLeaseMs;
    private final int maxAttempts;

    public PaymentProcessingTransactions(
            PaymentRepository payments,
            PaymentEventRepository events,
            @Value("${orchestrator.retry-delay-ms:5000}") long retryMs,
            @Value("${orchestrator.reconciliation-delay-ms:15000}") long reconciliationMs,
            @Value("${orchestrator.processing-lease-ms:30000}") long processingLeaseMs,
            @Value("${orchestrator.max-attempts:3}") int maxAttempts) {

        this.payments = payments;
        this.events = events;
        this.retryMs = retryMs;
        this.reconciliationMs = reconciliationMs;
        this.processingLeaseMs = processingLeaseMs;
        this.maxAttempts = maxAttempts;
    }


    @Transactional
    public Integer claim(UUID id) {
        Payment payment = locked(id);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return null;
        }

        Instant now = Instant.now();

        if (payment.getNextAttemptAt() != null
                && payment.getNextAttemptAt().isAfter(now)) {
            return null;
        }

        payment.incrementAttempts();

        payment.transition(
                PaymentStatus.PROCESSING,
                null,
                null,
                now.plusMillis(processingLeaseMs)
        );

        events.save(new PaymentEvent(
                id,
                "PAYMENT_ATTEMPTED",
                "Attempt " + payment.getAttempts()
        ));

        return payment.getAttempts();
    }


    @Transactional
    public PaymentResponse finalizeCharge(
            UUID id,
            int claimedAttempt,
            ProviderOutcome outcome){
        Payment payment = locked(id);

        if (payment.getAttempts() != claimedAttempt) {
            return PaymentResponse.from(payment);
        }

        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            return PaymentResponse.from(payment);
        }

        if (payment.isProcessingLeaseExpired(Instant.now())) {
            return PaymentResponse.from(payment);
        }

        switch (outcome.kind()) {
            case SUCCESS -> payment.transition(
                    PaymentStatus.SUCCEEDED,
                    null,
                    outcome.reference(),
                    null
            );

            case DECLINED -> payment.transition(
                    PaymentStatus.FAILED,
                    outcome.message(),
                    null,
                    null
            );

            case AMBIGUOUS_TIMEOUT -> payment.transition(
                    PaymentStatus.REQUIRES_RECONCILIATION,
                    outcome.message(),
                    null,
                    Instant.now().plusMillis(reconciliationMs)
            );

            case TRANSIENT_ERROR -> {
                if (payment.getAttempts() >= maxAttempts) {
                    payment.transition(
                            PaymentStatus.FAILED,
                            "Retry limit reached: " + outcome.message(),
                            null,
                            null
                    );
                } else {
                    payment.transition(
                            PaymentStatus.PENDING,
                            outcome.message(),
                            null,
                            Instant.now().plusMillis(
                                    retryMs * payment.getAttempts()
                            )
                    );
                }
            }
        }

        events.save(new PaymentEvent(
                id,
                "PAYMENT_" + payment.getStatus(),
                outcome.message()
        ));

        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse finalizeReconciliation(
            UUID id,
            ProviderOutcome outcome) {

        Payment payment = locked(id);

        if (payment.getStatus() != PaymentStatus.PROCESSING
                && payment.getStatus()
                != PaymentStatus.REQUIRES_RECONCILIATION) {
            return PaymentResponse.from(payment);
        }

        if (payment.getStatus() == PaymentStatus.PROCESSING
                && !payment.isProcessingLeaseExpired(Instant.now())) {
            return PaymentResponse.from(payment);
        }

        if (outcome.kind() == ProviderOutcome.Kind.SUCCESS) {
            payment.transition(
                    PaymentStatus.SUCCEEDED,
                    null,
                    outcome.reference(),
                    null
            );

            events.save(new PaymentEvent(
                    id,
                    "PAYMENT_RECONCILED",
                    "Confirmed with provider"
            ));
        } else {
            // Unknown does not mean the charge failed.
            // Never automatically recharge an uncertain payment.
            payment.transition(
                    PaymentStatus.REQUIRES_RECONCILIATION,
                    "Provider outcome uncertain: " + outcome.message(),
                    null,
                    Instant.now().plusMillis(reconciliationMs)
            );

            events.save(new PaymentEvent(
                    id,
                    "RECONCILIATION_PENDING",
                    outcome.message()
            ));
        }

        return PaymentResponse.from(payment);
    }

    private Payment locked(UUID id) {
        return payments.findLocked(id).orElseThrow(
                () -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Payment not found"
                )
        );
    }
}
