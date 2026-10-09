package dev.portfolio.payments.api;
import dev.portfolio.payments.service.PaymentService;
import dev.portfolio.payments.model.PaymentEvent;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/payments") public class PaymentController {
 private final PaymentService service;public PaymentController(PaymentService service){this.service=service;}
 @PostMapping public ResponseEntity<PaymentResponse> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PaymentRequest request){if(key.isBlank()||key.length()>120)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key must be 1-120 characters");return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.create(key,request));}
 @GetMapping("/{id}") public PaymentResponse get(@PathVariable UUID id){return service.get(id);}
 @PostMapping("/{id}/process") public PaymentResponse process(@PathVariable UUID id){return service.process(id);}
 @PostMapping("/{id}/reconcile") public PaymentResponse reconcile(@PathVariable UUID id){return service.reconcile(id);}
 @GetMapping("/{id}/events") public List<PaymentEvent> events(@PathVariable UUID id){return service.history(id);}
}
