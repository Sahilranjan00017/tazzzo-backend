package com.tazzzo.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Wire shapes. The customer view never shows staff identities, the assignee or the internal version. */
final class SupportDtos {

    private SupportDtos() { }

    record CustomerMessage(int id, String author, String text, String at) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CustomerCase(String caseId, String category, String orderId, String subject, String status, List<CustomerMessage> messages,
                        String createdAt, String updatedAt, String requestId) {
        static CustomerCase of(SupportCase s, String requestId) {
            return new CustomerCase(s.caseId(), s.category().name(), s.orderId(), s.subject(), s.status().name(),
                    s.messages().stream().map(m -> new CustomerMessage(m.id(), m.author().name(), m.text(), m.at().toString())).toList(),
                    s.createdAt().toString(), s.updatedAt().toString(), requestId);
        }
    }

    record CustomerSummary(String caseId, String category, String subject, String status, int messageCount, String updatedAt) {
        static CustomerSummary of(SupportCase s) {
            return new CustomerSummary(s.caseId(), s.category().name(), s.subject(), s.status().name(), s.messages().size(), s.updatedAt().toString());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CustomerPage(List<CustomerSummary> items, String nextCursor, String requestId) { }

    record StaffMessage(int id, String author, String staffId, String text, String at) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StaffCase(String caseId, String customerId, String category, String orderId, String subject, String status, String assignedTo,
                     long version, List<StaffMessage> messages, String createdAt, String updatedAt) {
        static StaffCase of(SupportCase s) {
            return new StaffCase(s.caseId(), s.customerId(), s.category().name(), s.orderId(), s.subject(), s.status().name(), s.assignedTo(),
                    s.version(), s.messages().stream().map(m -> new StaffMessage(m.id(), m.author().name(), m.staffId(), m.text(), m.at().toString())).toList(),
                    s.createdAt().toString(), s.updatedAt().toString());
        }
    }

    record StaffSummary(String caseId, String customerId, String category, String subject, String status, String assignedTo, int messageCount,
                        long version, String updatedAt) {
        static StaffSummary of(SupportCase s) {
            return new StaffSummary(s.caseId(), s.customerId(), s.category().name(), s.subject(), s.status().name(), s.assignedTo(),
                    s.messages().size(), s.version(), s.updatedAt().toString());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StaffPage(List<StaffSummary> items, String nextCursor) { }
}
