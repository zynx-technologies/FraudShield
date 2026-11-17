package com.fraudshield.strategy;

import com.fraudshield.common.web.ApiErrors;

/** Segregation of duties (design section 12): authors and approvers must be different people. */
public final class Approvals {
    private Approvals() {}

    public static void requireDifferentPerson(String approver, String author, String what) {
        if (approver.equalsIgnoreCase(author)) {
            throw new ApiErrors.Forbidden("You " + what + ", so a different person must approve it");
        }
    }

    public static String requireReason(String reason) {
        if (reason == null || reason.strip().length() < 5) {
            throw new ApiErrors.BadRequest("A reason of at least 5 characters is required");
        }
        if (reason.strip().length() > 500) throw new ApiErrors.BadRequest("The reason must be 500 characters or fewer");
        return reason.strip();
    }
}