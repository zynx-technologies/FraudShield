package com.fraudshield.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudshield.decision.events.DecisionEvent;
import com.fraudshield.decision.events.DecisionPublisher;
import com.fraudshield.decision.rules.RuleSnapshot;
import com.fraudshield.decision.rules.RuleSnapshotProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DecisionApiTest {

    @Autowired MockMvc mvc;
    @Autowired RuleSnapshotProvider rules;
    @Autowired DecisionPublisher publisher;

    @AfterEach
    void resetRules() {
        rules.install(RuleSnapshot.builtIn());
    }

    private ResultActions decide(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/decisions").with(user("switch").roles("CHANNEL"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String payment(String tx, String channel, String amount, String account, String payee,
                                  String device, boolean remote) {
        return """
                {"transactionId":"%s","channel":"%s","amount":%s,"currency":"GBP","accountToken":"%s",
                 "payeeToken":"%s","deviceId":"%s","session":{"remoteAccess":%s,"newLocation":false}}
                """.formatted(tx, channel, amount, account, payee, device, remote);
    }

    @Test
    void onlyPaymentChannelsCanAskForDecisions() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/decisions").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/decisions").with(user("a").roles("ANALYST")).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(payment("TX-1", "CARD", "10", "tok_x", "p", "d", false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void requiresIdempotencyKeyAndValidPayment() throws Exception {
        mvc.perform(post("/api/v1/decisions").with(user("switch").roles("CHANNEL")).contentType(MediaType.APPLICATION_JSON)
                        .content(payment("TX-2", "CARD", "10", "tok_a", "p", "d", false)))
                .andExpect(status().isBadRequest());
        decide("k-neg", payment("TX-3", "CARD", "-5", "tok_a", "p", "d", false)).andExpect(status().isBadRequest());
        decide("k-cur", payment("TX-4", "CARD", "5", "tok_a", "p", "d", false).replace("GBP", "pounds"))
                .andExpect(status().isBadRequest());
    }

    /** The design's example (section 5): first payment of GBP 9,850 to a new payee with a remote-access tool. */
    @Test
    void holdsTheDesignExampleAndRaisesAnAlert() throws Exception {
        decide("k-design", payment("TX-77120", "INSTANT", "9850.00", "tok_8fa1", "tok_21bd", "D-55", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("HOLD"))
                .andExpect(jsonPath("$.score").value(98))
                .andExpect(jsonPath("$.reasonCodes", hasItem("NEW_PAYEE_HIGH_AMOUNT")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("REMOTE_ACCESS")))
                .andExpect(jsonPath("$.modelVersion").value("fs-baseline-1"))
                .andExpect(jsonPath("$.rulesetVersion").value("rs-builtin"));

        DecisionEvent event = publisher.recent().stream()
                .filter(e -> e.transactionId().equals("TX-77120")).findFirst().orElseThrow();
        assertThat(event.score()).isEqualTo(98);
        assertThat(event.features().amountToMedian()).isGreaterThan(40);
        assertThat(event.features().payeeIncomingSenders7d()).isEqualTo(2);
    }

    @Test
    void allowsAnOrdinaryPayment() throws Exception {
        decide("k-ok", payment("TX-5", "CARD", "48.00", "tok_31c0", "tok_grocer", "D-31", false))
                .andExpect(jsonPath("$.outcome").value("ALLOW"))
                .andExpect(jsonPath("$.score").value(0));
    }

    @Test
    void retriesReturnTheSameDecisionAndReusedKeysAreRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = payment("TX-6", "TRANSFER", "120.00", "tok_retry", "tok_p", "D-r", false);
        String first = decide(key, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = decide(key, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        decide(key, body.replace("120.00", "121.00")).andExpect(status().isConflict());
    }

    @Test
    void sanctionedPayeeIsBlockedBeforeScoring() throws Exception {
        rules.install(new RuleSnapshot("rs-9", List.of(), Set.of("tok_sanctioned"), Set.of(),
                new RuleSnapshot.Thresholds(60, 80, 99, "th-1")));
        decide("k-sanc", payment("TX-7", "TRANSFER", "10.00", "tok_s", "tok_sanctioned", "D-s", false))
                .andExpect(jsonPath("$.outcome").value("BLOCK"))
                .andExpect(jsonPath("$.score").value(nullValue()))
                .andExpect(jsonPath("$.reasonCodes[0]").value("RULE_SANCTIONS"))
                .andExpect(jsonPath("$.rulesetVersion").value("rs-9"));
    }

    @Test
    void liveRulesRaiseTheOutcomeAndShadowRulesDoNot() throws Exception {
        rules.install(new RuleSnapshot("rs-10", List.of(
                new RuleSnapshot.Rule("R-1", "Transfers over 5,000", "AMOUNT_OVER", "HOLD", "LIVE", Map.of("channel", "TRANSFER", "minAmount", "5000")),
                new RuleSnapshot.Rule("R-2", "Everything over 1", "AMOUNT_OVER", "BLOCK", "SHADOW", Map.of("minAmount", "1"))),
                Set.of(), Set.of(), new RuleSnapshot.Thresholds(60, 80, 99, "th-1")));
        decide("k-rule", payment("TX-8", "TRANSFER", "6000.00", "tok_r", "tok_rp", "D-rr", false))
                .andExpect(jsonPath("$.outcome").value("HOLD"))
                .andExpect(jsonPath("$.reasonCodes", hasItem("RULE_R-1")));
    }

    @Test
    void isNotReadyUntilRulesAreLoaded() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        rules.install(new RuleSnapshot("rs-5", List.of(), Set.of(), Set.of(), new RuleSnapshot.Thresholds(60, 80, 99, "th-1")));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void reportsPlatformStatusToStaff() throws Exception {
        mvc.perform(get("/api/v1/decisions/status").with(user("lead").roles("TEAM_LEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionApi").value("Healthy"))
                .andExpect(jsonPath("$.liveModel").value("fs-baseline-1"));
    }
}