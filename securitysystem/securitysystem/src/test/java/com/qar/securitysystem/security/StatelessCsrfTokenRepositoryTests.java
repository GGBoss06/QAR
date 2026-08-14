package com.qar.securitysystem.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StatelessCsrfTokenRepositoryTests {
    @Test
    void acceptsSignedTokenAndRejectsTampering() {
        StatelessCsrfTokenRepository repository = new StatelessCsrfTokenRepository(Duration.ofMinutes(5));
        CsrfToken issued = repository.generateToken(new MockHttpServletRequest());

        MockHttpServletRequest validRequest = new MockHttpServletRequest();
        validRequest.addHeader(StatelessCsrfTokenRepository.HEADER_NAME, issued.getToken());
        assertThat(repository.loadToken(validRequest)).isNotNull();
        assertThat(repository.loadToken(validRequest).getToken()).isEqualTo(issued.getToken());

        MockHttpServletRequest tamperedRequest = new MockHttpServletRequest();
        tamperedRequest.addHeader(StatelessCsrfTokenRepository.HEADER_NAME, issued.getToken() + "x");
        assertThat(repository.loadToken(tamperedRequest)).isNull();
    }
}
