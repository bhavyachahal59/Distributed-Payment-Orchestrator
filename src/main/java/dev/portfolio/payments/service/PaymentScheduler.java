package dev.portfolio.payments.service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
@Component public class PaymentScheduler {
 private static final Logger log=LoggerFactory.getLogger(PaymentScheduler.class);private final PaymentService service;
 public PaymentScheduler(PaymentService service){this.service=service;}
 @Scheduled(fixedDelayString="${orchestrator.scheduler-interval-ms:2000}") public void run(){for(var id:service.due()){try{service.process(id);}catch(Exception e){log.warn("Payment processing failed for {}: {}",id,e.toString());}}}
}
