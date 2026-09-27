package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpAuthPropertiesTest {

    private static OtpAuthProperties valid() {
        OtpAuthProperties p = new OtpAuthProperties();
        p.setTtlSeconds(300);
        p.setResendCooldownSeconds(30);
        p.setMaxAttempts(5);
        p.setGrantTtlSeconds(300);
        return p;
    }

    @Test void valid_policy_passes() {
        valid().validate();
    }

    @Test void zero_ttl_is_rejected() {
        OtpAuthProperties p = valid();
        p.setTtlSeconds(0);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test void negative_ttl_is_rejected() {
        OtpAuthProperties p = valid();
        p.setTtlSeconds(-1);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test void negative_cooldown_is_rejected() {
        OtpAuthProperties p = valid();
        p.setResendCooldownSeconds(-1);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test void zero_cooldown_is_allowed() {
        OtpAuthProperties p = valid();
        p.setResendCooldownSeconds(0);
        p.validate();
    }

    @Test void zero_max_attempts_is_rejected() {
        OtpAuthProperties p = valid();
        p.setMaxAttempts(0);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test void zero_grant_ttl_is_rejected() {
        OtpAuthProperties p = valid();
        p.setGrantTtlSeconds(0);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test void rate_limit_buckets_configured_requires_all_four() {
        OtpAuthProperties p = valid();
        assertThat(p.rateLimitBucketsConfigured()).isFalse();
        p.getRequestIp().setCapacity(10);
        p.getRequestIp().setRefillPerSecond(1);
        p.getRequestPhone().setCapacity(10);
        p.getRequestPhone().setRefillPerSecond(1);
        p.getVerifyIp().setCapacity(10);
        p.getVerifyIp().setRefillPerSecond(1);
        assertThat(p.rateLimitBucketsConfigured()).isFalse();
        p.getVerifyChallenge().setCapacity(10);
        p.getVerifyChallenge().setRefillPerSecond(1);
        assertThat(p.rateLimitBucketsConfigured()).isTrue();
    }
}
