package com.academy.paybridge.auth.domain;

import com.academy.paybridge.auth.api.UserRole;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_users_seq")
    @SequenceGenerator(name = "app_users_seq", sequenceName = "app_users_id_seq",
            initialValue = 1000, allocationSize = 1)
    private Long id;

    /** Stored lower-case. */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled = true;

    /** The account number this customer owns; null for an admin. */
    @Column(name = "account_number", length = 10)
    private String accountNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public AppUser(String email, String passwordHash, String fullName, UserRole role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    public void assignAccount(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public UserRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public String getAccountNumber() { return accountNumber; }
    public Instant getCreatedAt() { return createdAt; }
}
