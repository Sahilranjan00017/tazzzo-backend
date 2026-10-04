package com.tazzzo.account;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AccountDeletionErrorDto(String code, String message, @JsonProperty("request_id") String requestId) { }
