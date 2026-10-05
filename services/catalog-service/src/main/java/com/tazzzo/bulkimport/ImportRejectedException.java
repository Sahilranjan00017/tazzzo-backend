package com.tazzzo.bulkimport;

import java.util.List;

/** The file failed validation: nothing was written. Carries every row error, not just the first. */
final class ImportRejectedException extends RuntimeException {

    final transient List<BulkImportDtos.RowError> errors;

    ImportRejectedException(String message, List<BulkImportDtos.RowError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }
}
