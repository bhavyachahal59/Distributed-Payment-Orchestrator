
package dev.portfolio.payments.service;

import dev.portfolio.payments.api.PaymentRequest;
import dev.portfolio.payments.api.PaymentResponse;
import dev.portfolio.payments.model.Payment;
import dev.portfolio.payments.model.PaymentEvent;
import dev.portfolio.payments.repo.PaymentEventRepository;
import dev.portfolio.payments.repo.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCreationService {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;

    public PaymentCreationService(
            PaymentRepository payments,
            PaymentEventRepository events) {
        this.payments = payments;
        this.events = events;
    }

    @Transactional
    public PaymentResponse insert(String key, PaymentRequest request) {
        Payment payment = new Payment(
                key,
                request.merchantId(),
                request.amount(),
                request.currency()
        );

        payments.saveAndFlush(payment);

        events.save(new PaymentEvent(
                payment.getId(),
                "PAYMENT_CREATED",
                "Idempotency key registered"
        ));

        return PaymentResponse.from(payment);
    }
}
