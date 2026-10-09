package com.tazzzo.bulkimport.jobs;

import org.springframework.http.HttpStatus;

/** A request the job API refuses: the status it maps to and the documented code. */
public final class ImportJobException extends RuntimeException {

    public final HttpStatus status;
    public final String code;

    public ImportJobException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ImportJobException notFound(String id) {
        return new ImportJobException(HttpStatus.NOT_FOUND, "IMPORT_JOB_NOT_FOUND", "no import job " + id);
    }

    public static ImportJobException conflict(String message) {
        return new ImportJobException(HttpStatus.CONFLICT, "IMPORT_JOB_STATE", message);
    }

    public static ImportJobException invalid(String message) {
        return new ImportJobException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMPORT", message);
    }
}
