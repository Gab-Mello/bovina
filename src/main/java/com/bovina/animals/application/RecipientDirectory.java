package com.bovina.animals.application;

import com.bovina.platform.application.ApplicationFailure;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Tenant-scoped recipient identity check; clinical eligibility remains pilot-dependent. */
@Service
public class RecipientDirectory {
  private final NamedParameterJdbcTemplate jdbc;

  public RecipientDirectory(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Recipient requireActive(UUID tenant, UUID id) {
    var recipient = snapshots(tenant, List.of(id)).get(id);
    if (recipient == null) throw missing();
    if (!recipient.sex().equals("FEMALE") || !recipient.status().equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "RECIPIENT_NOT_ELIGIBLE",
          "A recipient cycle requires an active female animal");
    return recipient;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, Recipient> snapshots(UUID tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    if (ids.size() > 100)
      throw new IllegalArgumentException("Snapshot page exceeds 100 recipients");
    return jdbc
        .query(
            "SELECT id,name,sex,status FROM animal WHERE organization_id=:tenant AND id IN (:ids) ORDER BY id FOR SHARE",
            Map.of("tenant", tenant, "ids", ids),
            (rs, row) ->
                new Recipient(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("sex"),
                    rs.getString("status")))
        .stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(Recipient::id, r -> r));
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "RECIPIENT_NOT_FOUND", "Recipient animal not found");
  }

  public record Recipient(UUID id, String name, String sex, String status) {}
}
