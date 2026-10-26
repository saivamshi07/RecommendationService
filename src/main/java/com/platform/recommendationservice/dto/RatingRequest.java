package com.platform.recommendationservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RatingRequest {

    @NotBlank(message = "userId must not be blank")
    private String userId;

    @NotBlank(message = "itemId must not be blank")
    private String itemId;

    @NotNull(message = "rating must not be null")
    @DecimalMin(value = "0.00", inclusive = true, message = "rating must be at least 0.00")
    @DecimalMax(value = "5.00", inclusive = true, message = "rating must not exceed 5.00")
    @Digits(integer = 1, fraction = 2, message = "rating must have at most 1 integer digit and 2 decimal places (e.g. 3.75)")
    private BigDecimal rating;
}
