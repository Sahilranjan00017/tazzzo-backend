package com.tazzzo.account;


/** {@code status} is always {@code DELETED}: a repeat of an already-completed deletion reports the same end state. */
public record AccountDeletionResponseDto(String status, String requestId) { }
