package dev.portfolio.payments.repo;
import dev.portfolio.payments.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
public interface PaymentRepository extends JpaRepository<Payment,UUID> {
 Optional<Payment> findByIdempotencyKey(String key);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Payment p where p.id = :id") Optional<Payment> findLocked(@Param("id") UUID id);
 @Query("select p.id from Payment p where p.status in :statuses and p.nextAttemptAt <= :now order by p.createdAt") List<UUID> findDue(@Param("statuses") Collection<PaymentStatus> statuses,@Param("now") Instant now,org.springframework.data.domain.Pageable page);
}
