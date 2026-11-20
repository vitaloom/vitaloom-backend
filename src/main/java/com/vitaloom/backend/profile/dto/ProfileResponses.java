package com.vitaloom.backend.profile.dto;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class ProfileResponses {
    private ProfileResponses() {}
    public record Snapshot(Profile profile, String revision) {}
    public record Profile(String id, Map<String, Object> values, String updatedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String kind, Snapshot snapshot, List<String> applied, Map<String, String> errors,
                         String decision, String code, String detail, Object attempt) {
        public static Result success(Snapshot snapshot) { return new Result("success", snapshot, null, null, null, null, null, null); }
        public static Result partial(Snapshot snapshot, List<String> applied, Map<String, String> errors) { return new Result("partial-success", snapshot, applied, errors, null, null, null, null); }
        public static Result invalid(Map<String, String> errors) { return new Result("validation-error", null, null, errors, null, "INVALID_PROFILE", "Some profile fields are invalid.", null); }
        public static Result conflict(Snapshot snapshot) { return new Result("conflict", snapshot, null, null, null, "PROFILE_CONFLICT", "The profile changed. Reload it before saving.", null); }
        public static Result simple(String kind) { return new Result(kind, null, null, null, null, null, null, null); }
        public static Result decision(String value) { return new Result(null, null, null, null, value, null, null, null); }
        public static Result verified(Snapshot snapshot, Object attempt) { return new Result(null, snapshot, null, null, null, null, null, attempt); }
    }
}
