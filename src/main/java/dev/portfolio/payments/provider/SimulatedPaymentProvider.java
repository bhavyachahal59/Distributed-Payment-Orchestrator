package dev.portfolio.payments.provider;
import dev.portfolio.payments.model.Payment;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.math.BigDecimal;
/** Deterministic sandbox only. Provider state is memory-resident and resets on restart. */
@Component public class SimulatedPaymentProvider implements PaymentProvider {
 private final Map<UUID,ProviderOutcome> charges=new ConcurrentHashMap<>();
 @Override public ProviderOutcome charge(Payment p){
  ProviderOutcome existing=charges.get(p.getId());if(existing!=null)return existing;
  String merchant=p.getMerchantId().toLowerCase();
  if(merchant.startsWith("decline-"))return new ProviderOutcome(ProviderOutcome.Kind.DECLINED,null,"Sandbox decline");
  if(merchant.startsWith("retry-"))return new ProviderOutcome(ProviderOutcome.Kind.TRANSIENT_ERROR,null,"Sandbox temporary outage");
  String ref="sim_"+p.getId().toString().substring(0,12);
  ProviderOutcome success=new ProviderOutcome(ProviderOutcome.Kind.SUCCESS,ref,"Provider accepted payment");
  charges.putIfAbsent(p.getId(),success);
  if(merchant.startsWith("timeout-"))return new ProviderOutcome(ProviderOutcome.Kind.AMBIGUOUS_TIMEOUT,null,"Provider accepted payment but response timed out");
  return success;
 }
 @Override public ProviderOutcome lookup(UUID id){return charges.getOrDefault(id,new ProviderOutcome(ProviderOutcome.Kind.TRANSIENT_ERROR,null,"Provider record not found"));}
}
