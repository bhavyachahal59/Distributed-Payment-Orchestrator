package dev.portfolio.payments.repo;
import dev.portfolio.payments.model.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PaymentEventRepository extends JpaRepository<PaymentEvent,UUID>{List<PaymentEvent> findByPaymentIdOrderByOccurredAtAsc(UUID paymentId);}
