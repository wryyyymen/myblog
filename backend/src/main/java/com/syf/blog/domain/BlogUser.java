package com.syf.blog.domain;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "users")
public class BlogUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true,length = 50)
    private String username;

    @Column(nullable = false,length = 100)
    private String passwordHash;

    @Column(name = "role_code", nullable = false, length = 32)
    private String roleCode = "USER";

    @Column(
            name = "is_enabled",
            nullable = false,
            columnDefinition = "TINYINT(1) DEFAULT 1"
    )

    private Boolean enabled = true;

    public BlogUser() {

    }

    public BlogUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }
}
