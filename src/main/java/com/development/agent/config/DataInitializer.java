package com.development.agent.config;

import com.development.agent.entity.Role;
import com.development.agent.entity.User;
import com.development.agent.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.initial-admin.username:admin}")
    private String adminUsername;

    @Value("${app.initial-admin.password:}")
    private String adminPassword;

    @Value("${app.initial-admin.email:}")
    private String adminEmail;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // Bootstrap an admin only when none exists yet (an admin may already have
        // been created manually). The password MUST be provided explicitly via
        // ADMIN_PASSWORD: there is no default, so a known/weak credential can never
        // be provisioned by accident. Refuse to start rather than run without an
        // administrative account.
        if (userRepository.countByRole(Role.ADMIN) == 0) {
            if (userRepository.existsByUsername(adminUsername)) {
                log.warn("Admin bootstrap skipped: username '{}' is already taken by a non-admin user. " +
                        "Promote that user or configure a different app.initial-admin.username.", adminUsername);
                return;
            }
            if (adminPassword == null || adminPassword.isBlank() || adminPassword.length() < 8) {
                throw new IllegalStateException(
                        "Cannot bootstrap the initial admin: ADMIN_PASSWORD must be set to a strong password " +
                        "of at least 8 characters. Refusing to start with a missing or weak admin credential.");
            }
            String email = (adminEmail == null || adminEmail.isBlank())
                    ? adminUsername + "@localhost"
                    : adminEmail;
            User admin = new User(adminUsername, email, passwordEncoder.encode(adminPassword));
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);
            log.info("Bootstrap admin created (username: '{}', email: '{}'). Password supplied via ADMIN_PASSWORD.",
                    adminUsername, email);
        }
    }
}
