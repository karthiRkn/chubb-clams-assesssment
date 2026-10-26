package com.chubb.claims.claim;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The details a claimant may correct while the claim is still SUBMITTED. Every field is optional and an absent field is left
 * as it is. Not changeable here: the claim number, the claimant, the market, the currency, the status, the assignment and
 * everything an officer records. The same rules apply as when the claim was created.
 */
// "(?s)" lets a description run over several lines: without it "." stops at a line break and a perfectly good multi-line text
// would be refused as blank, although creating the claim with the same text works.
public record UpdateClaimRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") @Size(max = 200) String claimantName,
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") @Email @Size(max = 254) String claimantEmail,
        @Pattern(regexp = CreateClaimRequest.POLICY_NUMBER, message = CreateClaimRequest.POLICY_NUMBER_MESSAGE) String policyNumber,
        ClaimType claimType,
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") @Size(max = 2000) String incidentDescription,
        @PastOrPresent LocalDate incidentDate,
        @Positive @Digits(integer = 17, fraction = 2) BigDecimal claimedAmount) {

    public boolean isEmpty() {
        return claimantName == null && claimantEmail == null && policyNumber == null && claimType == null
                && incidentDescription == null && incidentDate == null && claimedAmount == null;
    }
}
