
package dev.portfolio.payments.service;

import dev.portfolio.payments.api.*;
import dev.portfolio.payments.model.*;
import dev.portfolio.payments.provider.*;
import dev.portfolio.payments.repo.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class PaymentService {

 private final PaymentRepository payments;
 private final PaymentEventRepository events;
 private final PaymentProvider provider;
 private final PaymentCreationService creationService;
 private final PaymentProcessingTransactions transactions;

 public PaymentService(
         PaymentCreationService creationService,
         PaymentProcessingTransactions transactions,
         PaymentRepository payments,
         PaymentEventRepository events,
         PaymentProvider provider) {

  this.creationService = creationService;
  this.transactions = transactions;
  this.payments = payments;
  this.events = events;
  this.provider = provider;
 }

 public PaymentResponse create(String key, PaymentRequest req) {
  var existing = payments.findByIdempotencyKey(key);

  if (existing.isPresent()) {
   return matching(existing.get(), req);
  }

  try {
   return creationService.insert(key, req);
  } catch (DataIntegrityViolationException exception) {
   for (int attempt = 0; attempt < 10; attempt++) {
    var winner = payments.findByIdempotencyKey(key);

    if (winner.isPresent()) {
     return matching(winner.get(), req);
    }

    try {
     Thread.sleep(50);
    } catch (InterruptedException interrupted) {
     Thread.currentThread().interrupt();
     throw new ResponseStatusException(
             HttpStatus.SERVICE_UNAVAILABLE,
             "Interrupted while resolving concurrent payment",
             interrupted
     );
    }
   }

   throw new ResponseStatusException(
           HttpStatus.CONFLICT,
           "Concurrent payment creation could not be resolved; retry",
           exception
   );
  }
 }

 private PaymentResponse matching(Payment payment, PaymentRequest req) {
  if (!payment.getMerchantId().equals(req.merchantId())
          || payment.getAmount().compareTo(req.amount()) != 0
          || !payment.getCurrency().equals(req.currency())) {
   throw new ResponseStatusException(
           HttpStatus.CONFLICT,
           "Idempotency key reused with different payment details"
   );
  }

  return PaymentResponse.from(payment);
 }

 @Transactional(readOnly = true)
 public PaymentResponse get(UUID id) {
  return PaymentResponse.from(find(id));
 }

 @Transactional(readOnly = true)
 public List<PaymentEvent> history(UUID id) {
  if (!payments.existsById(id)) {
   throw new ResponseStatusException(
           HttpStatus.NOT_FOUND,
           "Payment not found"
   );
  }

  return events.findByPaymentIdOrderByOccurredAtAsc(id);
 }

 public PaymentResponse process(UUID id) {
  Payment payment = find(id);

  if (payment.getStatus() == PaymentStatus.SUCCEEDED
          || payment.getStatus() == PaymentStatus.FAILED) {
   return PaymentResponse.from(payment);
  }

  if (payment.getStatus()
          == PaymentStatus.REQUIRES_RECONCILIATION) {
   return reconcile(id);
  }

  if (payment.getStatus() == PaymentStatus.PROCESSING) {
   if (payment.isProcessingLeaseExpired(Instant.now())) {
    return reconcile(id);
   }
   return PaymentResponse.from(payment);
  }

  Integer claimedAttempt = transactions.claim(id);

  if (claimedAttempt == null) {
   return PaymentResponse.from(find(id));
  }

  // No database transaction is held during the provider call.
  ProviderOutcome outcome;

  try {
   outcome = provider.charge(find(id));
  } catch (Exception exception) {
   // An exception may occur after the provider charged.
   // Treat it as an ambiguous outcome.
   outcome = new ProviderOutcome(
           ProviderOutcome.Kind.AMBIGUOUS_TIMEOUT,
           null,
           "Provider call failed: " + exception.getMessage()
   );
  }

  return transactions.finalizeCharge(id, claimedAttempt, outcome);
 }

 public PaymentResponse reconcile(UUID id) {
  Payment payment = find(id);

  if (payment.getStatus()
          != PaymentStatus.REQUIRES_RECONCILIATION
          && payment.getStatus() != PaymentStatus.PROCESSING) {
   return PaymentResponse.from(payment);
  }

  if (payment.getStatus() == PaymentStatus.PROCESSING
          && !payment.isProcessingLeaseExpired(Instant.now())) {
   return PaymentResponse.from(payment);
  }

  ProviderOutcome outcome;

  try {
   outcome = provider.lookup(id);
  } catch (Exception exception) {
   outcome = new ProviderOutcome(
           ProviderOutcome.Kind.TRANSIENT_ERROR,
           null,
           "Provider lookup failed: " + exception.getMessage()
   );
  }

  return transactions.finalizeReconciliation(id, outcome);
 }

 @Transactional(readOnly = true)
 public List<UUID> due() {
  return payments.findDue(
          List.of(
                  PaymentStatus.PENDING,
                  PaymentStatus.PROCESSING,
                  PaymentStatus.REQUIRES_RECONCILIATION
          ),
          Instant.now(),
          PageRequest.of(0, 50)
  );
 }

 private Payment find(UUID id) {
  return payments.findById(id).orElseThrow(
          () -> new ResponseStatusException(
                  HttpStatus.NOT_FOUND,
                  "Payment not found"
          )
  );
 }
}
