
package dev.portfolio.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.portfolio.payments.repo.PaymentRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5433/payments",
        "spring.datasource.username=payments",
        "spring.datasource.password=localdev",
        "orchestrator.scheduler-interval-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
class PostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PaymentRepository payments;

    @Test
    void concurrentRequestsCreateExactlyOnePayment() throws Exception {
        int count = 10;
        String key = "pg-test-" + UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start timeout");
                    }

                    return mvc.perform(post("/api/v1/payments")
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                {
                                  "merchantId": "postgres-concurrent",
                                  "amount": 5000.00,
                                  "currency": "INR"
                                }
                                """))
                            .andExpect(status().isAccepted())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
                }));
            }

            Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            String expectedId = null;

            for (Future<String> future : futures) {
                JsonNode response =
                        mapper.readTree(future.get(30, TimeUnit.SECONDS));

                String actualId = response.get("id").asText();

                if (expectedId == null) {
                    expectedId = actualId;
                } else {
                    Assertions.assertEquals(expectedId, actualId);
                }
            }

            var saved = payments.findByIdempotencyKey(key);

            Assertions.assertTrue(saved.isPresent());
            Assertions.assertEquals(expectedId, saved.get().getId().toString());

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
