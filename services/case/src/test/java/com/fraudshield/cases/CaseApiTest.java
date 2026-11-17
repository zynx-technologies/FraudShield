package com.fraudshield.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseApiTest {

    static final String ANALYST = "j.analyst@example.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private static RequestPostProcessor analyst() { return user(ANALYST).roles("ANALYST"); }

    private static RequestPostProcessor lead() { return user("t.lead@example.test").roles("TEAM_LEAD"); }

    private static RequestPostProcessor engine() { return user("decision").roles("DECISION_ENGINE"); }

    @Test
    void healthIsPublicAndEverythingElseNeedsTheRightRole() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/alerts")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/alerts").with(user("s").roles("STRATEGIST"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/alerts").with(analyst()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"decisionId":"DE-X","transactionId":"TX-X","channel":"CARD","amount":1,"currency":"GBP",
                         "accountToken":"tok_x","score":90,"outcome":"HOLD","decidedAt":"2026-11-14T10:00:00Z"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void myQueueIsOrderedByRiskThenTimeLeft() throws Exception {
        mvc.perform(get("/api/v1/alerts?scope=mine").with(analyst()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("AL-90412"))
                .andExpect(jsonPath("$.items[0].score").value(98))
                .andExpect(jsonPath("$.items[0].decision").value("Held"))
                .andExpect(jsonPath("$.items[1].score").value(96))
                .andExpect(jsonPath("$.team", greaterThan(40)));
        mvc.perform(get("/api/v1/alerts?scope=team&channel=CARD&minScore=90").with(lead()))
                .andExpect(jsonPath("$.items[*].channel", hasItem("CARD")));
    }

    @Test
    void decisionServiceAlertsAreIdempotent() throws Exception {
        String body = """
                {"decisionId":"DE-TEST1","transactionId":"TX-T1","channel":"INSTANT","amount":2500.00,"currency":"GBP",
                 "accountToken":"tok_new1","payeeToken":"tok_p1","deviceId":"D-1","score":91,"outcome":"HOLD",
                 "reasons":[{"code":"REMOTE_ACCESS","label":"Remote-access tool active","value":0.22}],
                 "ruleHits":[],"modelVersion":"fs-baseline-1","rulesetVersion":"rs-1","latencyMs":40,"fallback":false,
                 "features":{"newPayee":true},"decidedAt":"2026-11-14T10:00:00Z","thresholdsVersion":"th-1"}""";
        String first = mvc.perform(post("/api/v1/alerts").with(engine()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/alerts").with(engine()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT main_reason FROM alert WHERE decision_id = 'DE-TEST1'").query(String.class).single())
                .isEqualTo("Remote-access tool active");
    }

    @Test
    void caseShowsTransactionReasonsLinksAndHistory() throws Exception {
        mvc.perform(get("/api/v1/cases/CS-20417").with(analyst()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(98))
                .andExpect(jsonPath("$.transaction.amount").value(9850.00))
                .andExpect(jsonPath("$.transaction.accountMasked").value("****8fa1"))
                .andExpect(jsonPath("$.transaction.payeeFirstPayment").value(true))
                .andExpect(jsonPath("$.transaction.deviceFirstSeenToday").value(true))
                .andExpect(jsonPath("$.reasons", hasSize(4)))
                .andExpect(jsonPath("$.reasons[0].value").value(0.31))
                .andExpect(jsonPath("$.links.nodes[*].id", hasItem("tok_7741")))
                .andExpect(jsonPath("$.links.nodes[?(@.type == 'IP')].flagged", hasItem(true)))
                .andExpect(jsonPath("$.history[*].type", hasItem("ASSIGNED")));
        mvc.perform(get("/api/v1/cases/CS-NOPE").with(analyst())).andExpect(status().isNotFound());
    }

    @Test
    void actionsNeedAReasonAndClosedCasesNeedATeamLeadToCorrect() throws Exception {
        String url = "/api/v1/cases/CS-20425/actions";
        mvc.perform(post(url).with(analyst()).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CONFIRM_FRAUD\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(analyst()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM_FRAUD\",\"reason\":\"Customer did not make this payment\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.outcome.label").value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.assignedTo").value(ANALYST));
        mvc.perform(post(url).with(analyst()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_GENUINE\",\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(url).with(lead()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_GENUINE\",\"reason\":\"Customer later confirmed the payment\"}"))
                .andExpect(jsonPath("$.outcome.label").value("GENUINE"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outcome WHERE case_id = 'CS-20425'").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void assignNextTakesTheMostUrgentUnassignedAlert() throws Exception {
        mvc.perform(post("/api/v1/alerts/assign-next").with(user("b.analyst@example.test").roles("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value("b.analyst@example.test"));
    }

    @Test
    void overviewSummarisesVolumeQueuesAndHealth() throws Exception {
        mvc.perform(get("/api/v1/overview").with(lead()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.transactionsToday", greaterThan(0)))
                .andExpect(jsonPath("$.kpis.alertsWaiting", greaterThan(10)))
                .andExpect(jsonPath("$.kpis.falsePositiveRatio", greaterThan(3.0)))
                .andExpect(jsonPath("$.topReasons", hasSize(5)))
                .andExpect(jsonPath("$.queueByChannel", hasSize(3)))
                .andExpect(jsonPath("$.platformHealth.decisionApi").value("Unavailable"));
    }

    @Test
    void decisionCountsAccumulate() throws Exception {
        String body = "[{\"hour\":\"2026-01-01T10:00:00Z\",\"channel\":\"CARD\",\"outcome\":\"ALLOW\",\"decisions\":5,\"latencyMsTotal\":100,\"latencyMsMax\":40}]";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/decision-events").with(engine()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
        }
        assertThat(jdbc.sql("SELECT decisions FROM decision_hourly WHERE channel = 'CARD' AND outcome = 'ALLOW' AND latency_ms_total = 200")
                .query(Long.class).single()).isEqualTo(10);
    }
}