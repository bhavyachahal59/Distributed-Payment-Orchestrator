package dev.portfolio.payments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Assertions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1","orchestrator.scheduler-interval-ms=3600000"}) @AutoConfigureMockMvc class PaymentIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
 String body(String merchant){return "{\"merchantId\":\""+merchant+"\",\"amount\":100.50,\"currency\":\"INR\"}";}
 String create(String key,String merchant)throws Exception{return mvc.perform(post("/api/v1/payments").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(merchant))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();}
 @Test void idempotencyReturnsSamePayment()throws Exception{String key=UUID.randomUUID().toString();String a=create(key,"shop-1");String b=create(key,"shop-1");org.junit.jupiter.api.Assertions.assertEquals(mapper.readTree(a).get("id"),mapper.readTree(b).get("id"));}
 @Test void conflictingIdempotencyKeyReturns409()throws Exception{String key=UUID.randomUUID().toString();create(key,"shop-1");mvc.perform(post("/api/v1/payments").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body("shop-2"))).andExpect(status().isConflict());}
 @Test void successfulPaymentAndHistory()throws Exception{JsonNode p=mapper.readTree(create(UUID.randomUUID().toString(),"shop-1"));String id=p.get("id").asText();mvc.perform(post("/api/v1/payments/"+id+"/process")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));mvc.perform(get("/api/v1/payments/"+id+"/events")).andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(greaterThanOrEqualTo(3))));}
 @Test void timeoutReconcilesWithoutSecondCharge()throws Exception{JsonNode p=mapper.readTree(create(UUID.randomUUID().toString(),"timeout-merchant"));String id=p.get("id").asText();mvc.perform(post("/api/v1/payments/"+id+"/process")).andExpect(jsonPath("$.status").value("REQUIRES_RECONCILIATION"));mvc.perform(post("/api/v1/payments/"+id+"/reconcile")).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.attempts").value(1));}
 @Test void providerDeclineIsFinal()throws Exception{JsonNode p=mapper.readTree(create(UUID.randomUUID().toString(),"decline-merchant"));mvc.perform(post("/api/v1/payments/"+p.get("id").asText()+"/process")).andExpect(jsonPath("$.status").value("FAILED"));}
 @Test void invalidAmountRejected()throws Exception{mvc.perform(post("/api/v1/payments").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"merchantId\":\"shop\",\"amount\":-1,\"currency\":\"INR\"}")).andExpect(status().isBadRequest());}

 @Test
 void concurrentRequestsReturnSamePayment() throws Exception {
  int requestCount = 10;
  String key = "concurrent-" + UUID.randomUUID();

  ExecutorService executor =
          Executors.newFixedThreadPool(requestCount);

  CountDownLatch ready = new CountDownLatch(requestCount);
  CountDownLatch start = new CountDownLatch(1);

  try {
   List<Future<String>> futures = new ArrayList<>();

   for (int i = 0; i < requestCount; i++) {
    futures.add(executor.submit(() -> {
     ready.countDown();

     if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException(
              "Timed out waiting to start"
      );
     }

     return mvc.perform(
                     post("/api/v1/payments")
                             .header("Idempotency-Key", key)
                             .contentType(MediaType.APPLICATION_JSON)
                             .content(body("concurrent-shop"))
             )
             .andExpect(status().isAccepted())
             .andReturn()
             .getResponse()
             .getContentAsString();
    }));
   }

   Assertions.assertTrue(
           ready.await(10, TimeUnit.SECONDS),
           "Not all requests were ready"
   );

   start.countDown();

   String expectedId = null;

   for (Future<String> future : futures) {
    String response = future.get(20, TimeUnit.SECONDS);
    String actualId =
            mapper.readTree(response).get("id").asText();

    if (expectedId == null) {
     expectedId = actualId;
    } else {
     Assertions.assertEquals(
             expectedId,
             actualId,
             "Concurrent requests created different payments"
     );
    }
   }

   Assertions.assertNotNull(expectedId);

  } finally {
   start.countDown();
   executor.shutdownNow();
  }
 }

}
