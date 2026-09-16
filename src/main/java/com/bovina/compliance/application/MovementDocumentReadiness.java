package com.bovina.compliance.application;

import com.bovina.platform.application.ApplicationFailure;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MovementDocumentReadiness {
  public Evaluation evaluate(
      UUID origin, UUID recipient, String country, String purpose, Instant movementAt) {
    // The official contextual matrix is unverified. Document presence cannot substitute for it.
    return new Evaluation(
        "UNKNOWN",
        null,
        "BLOCKED_BY_REGULATORY_SPEC_VERIFICATION_MOVEMENT_DOCUMENT_MATRIX",
        origin,
        recipient,
        country,
        purpose,
        movementAt);
  }

  public void requireDispatch(Evaluation result, boolean regulated) {
    if (regulated && !result.result().equals("PASS") && !result.result().equals("NOT_APPLICABLE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "MOVEMENT_DOCUMENT_REQUIREMENTS_UNVERIFIED",
          result.explanationCode());
  }

  public record Evaluation(
      String result,
      String ruleVersion,
      String explanationCode,
      UUID originEstablishmentId,
      UUID destinationRecipientId,
      String destinationCountry,
      String purpose,
      Instant movementAt) {}
}
