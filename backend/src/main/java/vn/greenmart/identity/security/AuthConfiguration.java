package vn.greenmart.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;

@Configuration
public class AuthConfiguration {
    @Bean public Clock authClock() { return Clock.systemUTC(); }
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(10); }
}
