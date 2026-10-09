package dev.portfolio.payments.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="payment_events") public class PaymentEvent {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false) private UUID paymentId;
 @Column(nullable=false) private String eventType;
 @Column(nullable=false) private Instant occurredAt;
 @Column(length=1000) private String details;
 protected PaymentEvent(){}
 public PaymentEvent(UUID paymentId,String eventType,String details){this.paymentId=paymentId;this.eventType=eventType;this.details=details;this.occurredAt=Instant.now();}
 public UUID getId(){return id;} public UUID getPaymentId(){return paymentId;} public String getEventType(){return eventType;} public Instant getOccurredAt(){return occurredAt;} public String getDetails(){return details;}
}
