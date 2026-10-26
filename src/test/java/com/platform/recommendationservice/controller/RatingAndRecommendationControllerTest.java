package com.platform.recommendationservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.recommendationservice.dto.RatingRequest;
import com.platform.recommendationservice.exception.GlobalExceptionHandler;
import com.platform.recommendationservice.repository.UserRatingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RatingAndRecommendationControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRatingRepository userRatingRepository;

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        userRatingRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/v1/ratings - Success with 2-digit fractional rating")
    void testPostRatingSuccess() throws Exception {
        RatingRequest request = RatingRequest.builder()
                .userId("d8142340-9a29-45bc-8a71-123456789abc")
                .itemId("movie-777")
                .rating(new BigDecimal("3.75"))
                .build();

        mockMvc.perform(post("/api/v1/ratings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("d8142340-9a29-45bc-8a71-123456789abc"))
                .andExpect(jsonPath("$.itemId").value("movie-777"))
                .andExpect(jsonPath("$.rating").value(3.75));
    }

    @Test
    @DisplayName("POST /api/v1/ratings - Validation error when rating > 5.00")
    void testPostRatingValidationError_ExceedsMax() throws Exception {
        RatingRequest request = RatingRequest.builder()
                .userId("user-1")
                .itemId("movie-1")
                .rating(new BigDecimal("5.50"))
                .build();

        mockMvc.perform(post("/api/v1/ratings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.rating").exists());
    }

    @Test
    @DisplayName("POST /api/v1/ratings - Validation error when rating has > 2 decimal places")
    void testPostRatingValidationError_TooManyDecimals() throws Exception {
        RatingRequest request = RatingRequest.builder()
                .userId("user-1")
                .itemId("movie-1")
                .rating(new BigDecimal("3.755"))
                .build();

        mockMvc.perform(post("/api/v1/ratings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.rating").exists());
    }

    @Test
    @DisplayName("GET /api/v1/recommendations/{userId} - Returns empty when no fallback and no ratings")
    void testGetRecommendationsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/unknown-user")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("unknown-user"))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.itemIds").isArray());
    }
}
