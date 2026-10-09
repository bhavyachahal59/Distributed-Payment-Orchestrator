
package dev.portfolio.payments;

import dev.portfolio.payments.api.PaymentResponse;
import dev.portfolio.payments.model.Payment;
import dev.portfolio.payments.model.PaymentStatus;
import dev.portfolio.payments.provider.ProviderOutcome;
import dev.portfolio.payments.repo.PaymentRepository;
import dev.portfolio.payments.service.PaymentProcessingTransactions;
import dev.portfolio.payments.service.PaymentService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recoverydb;DB_CLOSE_DELAY=-1",
        "orchestrator.scheduler-interval-ms=3600000"
})
class PaymentRecoveryTest {

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentService service;

    @Autowired
    PaymentProcessingTransactions transactions;

    private Payment createPayment() {
        return payments.saveAndFlush(new Payment(
                UUID.randomUUID().toString(),
                "recovery-shop",
                new BigDecimal("100.50"),
                "INR"
        ));
    }

    @Test
    void expiredProcessingLeaseIsSelectedForRecovery() {
        Payment payment = createPayment();

        payment.incrementAttempts();
        payment.transition(
                PaymentStatus.PROCESSING,
                null,
                null,
                Instant.now().minusSeconds(10)
        );

        payments.saveAndFlush(payment);

        assertTrue(service.due().contains(payment.getId()));
    }

    @Test
    void staleChargeResultCannotOverwriteReconciledPayment() {
        Payment payment = createPayment();

        Integer attempt = transactions.claim(payment.getId());
        assertNotNull(attempt);

        PaymentResponse reconciled =
                transactions.finalizeReconciliation(
                        payment.getId(),
                        new ProviderOutcome(
                                ProviderOutcome.Kind.SUCCESS,
                                "provider-confirmed",
                                "Confirmed"
                        )
                );

        // An active lease prevents premature reconciliation.
        assertEquals(
                PaymentStatus.PROCESSING,
                reconciled.status()
        );

        PaymentResponse charged =
                transactions.finalizeCharge(
                        payment.getId(),
                        attempt,
                        new ProviderOutcome(
                                ProviderOutcome.Kind.SUCCESS,
                                "provider-success",
                                "Accepted"
                        )
                );

        assertEquals(PaymentStatus.SUCCEEDED, charged.status());
    }

    @Test
    void paymentCannotBeClaimedTwice() {
        Payment payment = createPayment();

        Integer first = transactions.claim(payment.getId());
        Integer second = transactions.claim(payment.getId());

        assertNotNull(first);
        assertNull(second);
    }


    @Test
    void expiredLeaseRejectsLateProviderSuccess() {
        Payment payment = createPayment();

        Integer attempt = transactions.claim(payment.getId());
        assertNotNull(attempt);

        // Simulate a processing lease expiring.
        Payment persisted = payments.findById(payment.getId()).orElseThrow();

        persisted.transition(
                PaymentStatus.PROCESSING,
                null,
                null,
                Instant.now().minusSeconds(10)
        );

        payments.saveAndFlush(persisted);

        PaymentResponse result = transactions.finalizeCharge(
                payment.getId(),
                attempt,
                new ProviderOutcome(
                        ProviderOutcome.Kind.SUCCESS,
                        "late-provider-reference",
                        "Late success"
                )
        );

        assertEquals(PaymentStatus.PROCESSING, result.status());

        // The expired payment remains eligible for reconciliation.
        assertTrue(service.due().contains(payment.getId()));
    }

    @Test
    void reconciliationWinsOverLateProviderResponse() {
        Payment payment = createPayment();

        Integer attempt = transactions.claim(payment.getId());
        assertNotNull(attempt);

        Payment persisted = payments.findById(payment.getId()).orElseThrow();

        persisted.transition(
                PaymentStatus.PROCESSING,
                null,
                null,
                Instant.now().minusSeconds(10)
        );

        payments.saveAndFlush(persisted);

        PaymentResponse reconciled = transactions.finalizeReconciliation(
                payment.getId(),
                new ProviderOutcome(
                        ProviderOutcome.Kind.SUCCESS,
                        "reconciled-reference",
                        "Confirmed by provider lookup"
                )
        );

        assertEquals(PaymentStatus.SUCCEEDED, reconciled.status());

        PaymentResponse lateResult = transactions.finalizeCharge(
                payment.getId(),
                attempt,
                new ProviderOutcome(
                        ProviderOutcome.Kind.DECLINED,
                        null,
                        "Delayed decline"
                )
        );

        assertEquals(PaymentStatus.SUCCEEDED, lateResult.status());

        Payment finalPayment = payments.findById(payment.getId()).orElseThrow();

        assertEquals(PaymentStatus.SUCCEEDED, finalPayment.getStatus());
        assertEquals(
                "reconciled-reference",
                finalPayment.getProviderReference()
        );
    }

}
