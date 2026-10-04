package com.tazzzo.account;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code status} is always {@code DELETED}: a repeat of an already-completed deletion reports the same end state. */
public record AccountDeletionResponseDto(String status, @JsonProperty("request_id") String requestId) { }
