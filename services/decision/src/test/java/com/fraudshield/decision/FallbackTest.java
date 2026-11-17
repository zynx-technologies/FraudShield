package com.fraudshield.decision;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Design section 4: if scoring is unavailable, fall back to rules with stricter limits by channel. */
@SpringBootTest(properties = "fraudshield.scoring.mode=rules-only")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FallbackTest {

    @Autowired MockMvc mvc;

    @Test
    void largeInstantPaymentIsHeldWithoutAScore() throws Exception {
        mvc.perform(post("/api/v1/decisions").with(user("switch").roles("CHANNEL")).header("Idempotency-Key", "fb-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"TX-F1","channel":"INSTANT","amount":2500.00,"currency":"GBP","accountToken":"tok_f"}
                                """))
                .andExpect(jsonPath("$.outcome").value("HOLD"))
                .andExpect(jsonPath("$.score").value(nullValue()))
                .andExpect(jsonPath("$.reasonCodes", hasItem("FALLBACK_RULES")));

        mvc.perform(get("/api/v1/decisions/status").with(user("lead").roles("TEAM_LEAD")))
                .andExpect(jsonPath("$.fallbackActive").value(true))
                .andExpect(jsonPath("$.decisionApi").value("Degraded"));
    }
}