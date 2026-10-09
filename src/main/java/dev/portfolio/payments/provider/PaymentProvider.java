package dev.portfolio.payments.provider;
import dev.portfolio.payments.model.Payment;
import java.util.UUID;
public interface PaymentProvider {ProviderOutcome charge(Payment payment); ProviderOutcome lookup(UUID paymentId);}
