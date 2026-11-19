package com.vitaloom.backend.assistant;

final class AssistantPersonalDataTools {
    private AssistantPersonalDataTools() {}

    static final class PersonalDataAccessDeniedException extends RuntimeException {
        PersonalDataAccessDeniedException() {
            super("Assistant personal data access is no longer authorized");
        }
    }
}
