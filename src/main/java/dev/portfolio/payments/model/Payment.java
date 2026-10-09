package dev.portfolio.payments.model;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="payments",uniqueConstraints=@UniqueConstraint(name="uk_payment_idempotency",columnNames="idempotency_key"))
public class Payment {
 @Id private UUID id;
 @Column(name="idempotency_key",nullable=false,updatable=false,length=120) private String idempotencyKey;
 @Column(nullable=false,updatable=false) private String merchantId;
 @Column(nullable=false,updatable=false,precision=19,scale=2) private BigDecimal amount;
 @Column(nullable=false,updatable=false,length=3) private String currency;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private PaymentStatus status;
 @Column(nullable=false) private int attempts;
 private String providerReference;
 private String failureReason;
 private Instant nextAttemptAt;
 @Column(nullable=false,updatable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
 @Version private long version;
 protected Payment(){}
 public Payment(String key,String merchant,BigDecimal amount,String currency){id=UUID.randomUUID();idempotencyKey=key;merchantId=merchant;this.amount=amount;this.currency=currency;status=PaymentStatus.PENDING;createdAt=Instant.now();updatedAt=createdAt;nextAttemptAt=createdAt;}
 public UUID getId(){return id;} public String getIdempotencyKey(){return idempotencyKey;} public String getMerchantId(){return merchantId;} public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;} public PaymentStatus getStatus(){return status;} public int getAttempts(){return attempts;} public String getProviderReference(){return providerReference;} public String getFailureReason(){return failureReason;} public Instant getNextAttemptAt(){return nextAttemptAt;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
 public void transition(PaymentStatus s,String reason,String ref,Instant next){status=s;failureReason=reason;if(ref!=null)providerReference=ref;nextAttemptAt=next;updatedAt=Instant.now();}
 public void incrementAttempts(){attempts++;updatedAt=Instant.now();}

 public boolean isProcessingLeaseExpired(Instant now) {
  return status == PaymentStatus.PROCESSING
          && nextAttemptAt != null
          && !nextAttemptAt.isAfter(now);
 }

}
