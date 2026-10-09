package dev.portfolio.payments.service;
import dev.portfolio.payments.api.*;
import dev.portfolio.payments.model.*;
import dev.portfolio.payments.repo.*;
import dev.portfolio.payments.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
@Service public class PaymentService {
 private final PaymentRepository payments;private final PaymentEventRepository events;private final PaymentProvider provider;
 private final long retryMs;private final long reconciliationMs;private final int maxAttempts;private final PaymentCreationService creationService;


 public PaymentService(
         PaymentCreationService creationService,
         PaymentRepository payments,
         PaymentEventRepository events,
         PaymentProvider provider,
         @Value("${orchestrator.retry-delay-ms:5000}") long retryMs,
         @Value("${orchestrator.reconciliation-delay-ms:15000}") long reconciliationMs,
         @Value("${orchestrator.max-attempts:3}") int maxAttempts) {

  this.creationService = creationService;
  this.payments = payments;
  this.events = events;
  this.provider = provider;
  this.retryMs = retryMs;
  this.reconciliationMs = reconciliationMs;
  this.maxAttempts = maxAttempts;
 }


 public PaymentResponse create(String key, PaymentRequest req) {

  var existing = payments.findByIdempotencyKey(key);

  if (existing.isPresent()) {
   return matching(existing.get(), req);
  }

  try {
   return creationService.insert(key, req);
  } catch (DataIntegrityViolationException exception) {

   // Another request may have inserted the same key.
   // Its transaction has to become visible before we read it.

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

 private PaymentResponse matching(Payment p,PaymentRequest r){if(!p.getMerchantId().equals(r.merchantId())||p.getAmount().compareTo(r.amount())!=0||!p.getCurrency().equals(r.currency()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key reused with different payment details");return PaymentResponse.from(p);}
 @Transactional(readOnly=true) public PaymentResponse get(UUID id){return PaymentResponse.from(payments.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found")));}
 @Transactional(readOnly=true) public List<PaymentEvent> history(UUID id){if(!payments.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");return events.findByPaymentIdOrderByOccurredAtAsc(id);}
 @Transactional public PaymentResponse process(UUID id){
  Payment p=payments.findLocked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found"));
  if(p.getStatus()==PaymentStatus.SUCCEEDED||p.getStatus()==PaymentStatus.FAILED)return PaymentResponse.from(p);
  if(p.getStatus()==PaymentStatus.REQUIRES_RECONCILIATION)return reconcileLocked(p);
  if(p.getNextAttemptAt()!=null&&p.getNextAttemptAt().isAfter(Instant.now()))return PaymentResponse.from(p);
  p.incrementAttempts();p.transition(PaymentStatus.PROCESSING,null,null,null);
  events.save(new PaymentEvent(id,"PAYMENT_ATTEMPTED","Attempt "+p.getAttempts()));
  ProviderOutcome outcome=provider.charge(p);
  switch(outcome.kind()){
   case SUCCESS -> p.transition(PaymentStatus.SUCCEEDED,null,outcome.reference(),null);
   case DECLINED -> p.transition(PaymentStatus.FAILED,outcome.message(),null,null);
   case AMBIGUOUS_TIMEOUT -> p.transition(PaymentStatus.REQUIRES_RECONCILIATION,outcome.message(),null,Instant.now().plusMillis(reconciliationMs));
   case TRANSIENT_ERROR -> {if(p.getAttempts()>=maxAttempts)p.transition(PaymentStatus.FAILED,"Retry limit reached: "+outcome.message(),null,null);else p.transition(PaymentStatus.PENDING,outcome.message(),null,Instant.now().plusMillis(retryMs*p.getAttempts()));}
  }
  events.save(new PaymentEvent(id,"PAYMENT_"+p.getStatus(),outcome.message()));
  return PaymentResponse.from(p);
 }
 @Transactional public PaymentResponse reconcile(UUID id){Payment p=payments.findLocked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found"));return reconcileLocked(p);}
 private PaymentResponse reconcileLocked(Payment p){if(p.getStatus()!=PaymentStatus.REQUIRES_RECONCILIATION)return PaymentResponse.from(p);
  ProviderOutcome outcome=provider.lookup(p.getId());
  if(outcome.kind()==ProviderOutcome.Kind.SUCCESS){p.transition(PaymentStatus.SUCCEEDED,null,outcome.reference(),null);events.save(new PaymentEvent(p.getId(),"PAYMENT_RECONCILED","Confirmed with provider"));}
  else {p.transition(PaymentStatus.REQUIRES_RECONCILIATION,"Still uncertain: "+outcome.message(),null,Instant.now().plusMillis(reconciliationMs));events.save(new PaymentEvent(p.getId(),"RECONCILIATION_PENDING",outcome.message()));}
  return PaymentResponse.from(p);
 }
 @Transactional(readOnly=true) public List<UUID> due(){return payments.findDue(List.of(PaymentStatus.PENDING,PaymentStatus.REQUIRES_RECONCILIATION),Instant.now(),PageRequest.of(0,50));}
}
