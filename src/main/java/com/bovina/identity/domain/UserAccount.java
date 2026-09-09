package com.bovina.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccount {
  @Id private UUID id;

  @Column(nullable = false, length = 512)
  private String issuer;

  @Column(nullable = false, length = 255)
  private String subject;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Version private Long version;

  protected UserAccount() {}
}
