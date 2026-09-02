package com.development.agent.repository;

import com.development.agent.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    Optional<User> findByActiveToken(String token);
    long countByRole(String role);
    long countByEnabled(boolean enabled);
}
