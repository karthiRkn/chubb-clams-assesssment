package com.chubb.claims.assessment;

/** What an entry of the assessment history is: a note written at review, or the decision or settlement that followed. */
public enum AssessmentKind {
    NOTE,
    APPROVED,
    REJECTED,
    SETTLED
}
