package com.fraudshield.common.security;

/** Entra ID app roles on the fraudshield-api app registration. */
public final class Roles {
    /** Investigates alerts and decides outcomes. */
    public static final String ANALYST = "ANALYST";
    /** Watches queue health and service levels. */
    public static final String TEAM_LEAD = "TEAM_LEAD";
    /** Authors rules and proposes threshold changes. */
    public static final String STRATEGIST = "STRATEGIST";
    /** Second approver for rules and thresholds. Must not be the author. */
    public static final String RISK_APPROVER = "RISK_APPROVER";
    /** Requests model releases and reads model monitoring. */
    public static final String ML_ENGINEER = "ML_ENGINEER";
    /** Approves model releases. Must not have trained or requested the model. */
    public static final String MODEL_RISK_OWNER = "MODEL_RISK_OWNER";
    /** Payment channels that ask for decisions, through API Management. */
    public static final String CHANNEL = "CHANNEL";
    /** The decision service calling case and strategy services. */
    public static final String DECISION_ENGINE = "DECISION_ENGINE";

    private Roles() {}
}