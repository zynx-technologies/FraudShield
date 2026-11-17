package com.fraudshield.strategy;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StrategyApiTest {

    @Autowired MockMvc mvc;

    static RequestPostProcessor strategist() { return user("s.strategist@example.test").roles("STRATEGIST"); }

    static RequestPostProcessor approver() { return user("r.approver@example.test").roles("RISK_APPROVER"); }

    static RequestPostProcessor riskOwner() { return user("k.riskowner@example.test").roles("MODEL_RISK_OWNER"); }

    private ResultActions postJson(String url, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @Order(1)
    void listsRulesWithHitsPrecisionAndPendingChanges() throws Exception {
        mvc.perform(get("/api/v1/rules").with(strategist()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].action").value("BLOCK"))
                .andExpect(jsonPath("$[?(@.id == 'R-105')].status", hasItem("REVIEW")))
                .andExpect(jsonPath("$[?(@.id == 'R-105')].pending.author", hasItem("s.strategist@example.test")));
    }

    @Test
    @Order(2)
    void simulatorComparesCurrentAndProposedThresholds() throws Exception {
        postJson("/api/v1/thresholds/simulate", strategist(), "{\"stepUp\":60,\"hold\":78,\"block\":99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.fraudRecallPct", closeTo(78.4, 0.6)))
                .andExpect(jsonPath("$.current.alertsPerDay", org.hamcrest.Matchers.allOf(greaterThan(2228), org.hamcrest.Matchers.lessThan(2288))))
                .andExpect(jsonPath("$.current.falsePositiveRatio", closeTo(6.2, 0.2)))
                .andExpect(jsonPath("$.proposed.fraudRecallPct", greaterThan(78.4)))
                .andExpect(jsonPath("$.proposed.alertsPerDay", greaterThan(2300)));
        postJson("/api/v1/thresholds/simulate", strategist(), "{\"stepUp\":80,\"hold\":70,\"block\":99}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    void thresholdChangeNeedsASecondPerson() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(
                postJson("/api/v1/thresholds/proposals", strategist(), "{\"stepUp\":60,\"hold\":78,\"block\":99,\"reason\":\"Raise recall for APP scams\"}")
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").toString();
        postJson("/api/v1/thresholds/proposals", strategist(), "{\"stepUp\":61,\"hold\":78,\"block\":99,\"reason\":\"Another one\"}")
                .andExpect(status().isConflict());
        // The author holds both roles, but still cannot approve their own change.
        postJson("/api/v1/thresholds/proposals/" + id + "/approve",
                user("s.strategist@example.test").roles("STRATEGIST", "RISK_APPROVER"), "{\"reason\":\"Looks right\"}")
                .andExpect(status().isForbidden());
        postJson("/api/v1/thresholds/proposals/" + id + "/approve", strategist(), "{\"reason\":\"Looks right\"}")
                .andExpect(status().isForbidden());
        postJson("/api/v1/thresholds/proposals/" + id + "/approve", approver(), "{\"reason\":\"Reviewed simulation\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/rules/live").with(user("decision").roles("DECISION_ENGINE")))
                .andExpect(jsonPath("$.thresholds.hold").value(78))
                .andExpect(jsonPath("$.version").value("rs-204"));
    }

    @Test
    @Order(4)
    void newRulesWaitForApprovalAndUnknownTypesAreRefused() throws Exception {
        postJson("/api/v1/rules", strategist(), "{\"name\":\"Bad\",\"type\":\"NOPE\",\"action\":\"HOLD\"}").andExpect(status().isBadRequest());
        postJson("/api/v1/rules", strategist(), "{\"name\":\"Bad\",\"type\":\"AMOUNT_OVER\",\"action\":\"HOLD\",\"params\":{\"minAmount\":\"-1\"}}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/rules", strategist(), """
                {"name":"Transfers over £20,000","type":"AMOUNT_OVER","action":"HOLD","mode":"LIVE","params":{"minAmount":"20000","channel":"TRANSFER"}}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("R-107"))
                .andExpect(jsonPath("$.status").value("REVIEW"));
        mvc.perform(get("/api/v1/rules/live").with(user("decision").roles("DECISION_ENGINE")))
                .andExpect(jsonPath("$.rules[*].id", org.hamcrest.Matchers.not(hasItem("R-107"))));
        postJson("/api/v1/rules/R-107/versions/1/approve", approver(), "{\"reason\":\"Backtest reviewed\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/rules/live").with(user("decision").roles("DECISION_ENGINE")))
                .andExpect(jsonPath("$.rules[*].id", hasItem("R-107")))
                .andExpect(jsonPath("$.sanctionedPayees", hasItem("tok_sanctioned1")));
        mvc.perform(get("/api/v1/rules/history").with(strategist())).andExpect(jsonPath("$[0].ruleId").value("R-107"));
        mvc.perform(post("/api/v1/rules/R-102/backtest").with(strategist())).andExpect(status().isNotImplemented());
    }

    @Test
    @Order(5)
    void modelMonitoringComparesChampionAndChallenger() throws Exception {
        mvc.perform(get("/api/v1/models/fs-txn").with(user("m").roles("ML_ENGINEER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.champion.version").value(14))
                .andExpect(jsonPath("$.challenger.version").value(15))
                .andExpect(jsonPath("$.driftAlerts").value(1))
                .andExpect(jsonPath("$.driftAlertFeatures[0]").value("device_age_days"))
                .andExpect(jsonPath("$.weekly", hasSize(11)))
                .andExpect(jsonPath("$.pendingRelease").value(15));
    }

    @Test
    @Order(6)
    void releaseApprovalEnforcesSegregationOfDuties() throws Exception {
        String url = "/api/v1/models/fs-txn/releases/15";
        mvc.perform(get(url).with(riskOwner()))
                .andExpect(jsonPath("$.gates", hasSize(8)))
                .andExpect(jsonPath("$.readyForDecision").value(true));
        postJson(url + "/approve", user("m.engineer@example.test").roles("MODEL_RISK_OWNER"), "{\"reason\":\"I trained it\"}")
                .andExpect(status().isForbidden());
        postJson(url + "/approve", user("other").roles("ML_ENGINEER"), "{\"reason\":\"Approve\"}").andExpect(status().isForbidden());
        postJson(url + "/approve", riskOwner(), "{\"reason\":\"Gate evidence reviewed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.gates[7].status").value("PASSED"))
                .andExpect(jsonPath("$.gates[6].result", startsWith("Ready")));
        postJson(url + "/reject", riskOwner(), "{\"reason\":\"Too late\"}").andExpect(status().isConflict());
    }
}