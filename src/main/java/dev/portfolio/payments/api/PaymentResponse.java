package dev.portfolio.payments.api;
import dev.portfolio.payments.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record PaymentResponse(UUID id,String merchantId,BigDecimal amount,String currency,PaymentStatus status,int attempts,String providerReference,String failureReason,Instant createdAt,Instant updatedAt){
 public static PaymentResponse from(Payment p){return new PaymentResponse(p.getId(),p.getMerchantId(),p.getAmount(),p.getCurrency(),p.getStatus(),p.getAttempts(),p.getProviderReference(),p.getFailureReason(),p.getCreatedAt(),p.getUpdatedAt());}
}
