package com.acme.insurance.pas.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(PasClientProperties.class)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final PasClientProperties clientProperties;

    @Autowired
    public SecurityConfig(PasClientProperties clientProperties) {
        this.clientProperties = clientProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        List<UserDetails> users = new ArrayList<UserDetails>();
        List<PasClientProperties.Client> clients = clientProperties.getClients();
        if (clients != null) {
            for (PasClientProperties.Client client : clients) {
                if (!StringUtils.hasText(client.getUsername())
                        || !StringUtils.hasText(client.getPasswordHash())) {
                    continue;
                }

                List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
                authorities.add(new SimpleGrantedAuthority("ROLE_PAS_CLIENT"));
                if (client.getBranches() != null) {
                    for (String branch : client.getBranches()) {
                        if (StringUtils.hasText(branch)) {
                            authorities.add(new SimpleGrantedAuthority(
                                    "BRANCH_" + branch.trim().toUpperCase(Locale.ROOT)));
                        }
                    }
                }
                users.add(User.withUsername(client.getUsername().trim())
                        .password(client.getPasswordHash().trim())
                        .authorities(authorities)
                        .build());
            }
        }

        auth.userDetailsService(new InMemoryUserDetailsManager(users))
                .passwordEncoder(passwordEncoder());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .csrf().disable()
                .authorizeRequests()
                .antMatchers("/api/**").hasRole("PAS_CLIENT")
                .anyRequest().permitAll()
                .and()
                .httpBasic();
    }
}
