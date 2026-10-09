package dev.portfolio.payments.api;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record PaymentRequest(@NotBlank String merchantId,@NotNull @DecimalMin("0.01") @Digits(integer=15,fraction=2) BigDecimal amount,@NotBlank @Pattern(regexp="[A-Z]{3}") String currency){}
