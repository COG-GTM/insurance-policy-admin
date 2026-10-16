package com.acme.insurance.pas.config;

import com.acme.insurance.pas.config.ApiClientProperties.ApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP Basic authentication for the policy API.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private static final Pattern BCRYPT_PATTERN = Pattern.compile(
            "\\A\\$2a?\\$\\d\\d\\$[./0-9A-Za-z]{53}");

    private final ApiClientProperties apiClientProperties;

    @Autowired
    public SecurityConfig(ApiClientProperties apiClientProperties) {
        this.apiClientProperties = apiClientProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService apiClientUserDetailsService() {
        List<ApiClient> clients = apiClientProperties.getClients();
        if (clients.isEmpty()) {
            throw new IllegalStateException(
                    "No API clients configured. Define at least one "
                            + "pas.security.clients entry; the facade refuses "
                            + "to start without API clients.");
        }

        List<UserDetails> users = new ArrayList<UserDetails>();
        Set<String> usernames = new HashSet<String>();
        for (ApiClient client : clients) {
            if (client == null || !StringUtils.hasText(client.getUsername())
                    || !StringUtils.hasText(client.getPasswordHash())) {
                throw new IllegalStateException(
                        "Each pas.security.clients entry requires a username "
                                + "and a BCrypt passwordHash.");
            }
            if (!BCRYPT_PATTERN.matcher(client.getPasswordHash()).matches()) {
                throw new IllegalStateException(
                        "API client '" + client.getUsername()
                                + "' must have a valid BCrypt passwordHash.");
            }
            if (!usernames.add(client.getUsername().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "Duplicate API client username '"
                                + client.getUsername()
                                + "' (usernames are case-insensitive).");
            }
            users.add(new User(client.getUsername(), client.getPasswordHash(),
                    AuthorityUtils.createAuthorityList("ROLE_API_CLIENT")));
        }
        return new InMemoryUserDetailsManager(users);
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth)
            throws Exception {
        auth.userDetailsService(apiClientUserDetailsService())
                .passwordEncoder(passwordEncoder());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.antMatcher("/api/**")
                .authorizeRequests().anyRequest().authenticated()
                .and().httpBasic().realmName("PAS REST Facade")
                .and().sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and().csrf().disable();
    }
}
