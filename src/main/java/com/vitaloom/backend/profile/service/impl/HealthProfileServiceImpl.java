package com.vitaloom.backend.profile.service.impl;

import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.vitaloom.backend.auth.model.User;
import com.vitaloom.backend.profile.dto.ProfileRequests;
import com.vitaloom.backend.profile.dto.ProfileResponses;
import com.vitaloom.backend.profile.repository.HealthProfileRepository;
import com.vitaloom.backend.profile.service.HealthProfileService;
import com.vitaloom.backend.auth.exception.UserNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class HealthProfileServiceImpl implements HealthProfileService {
    private static final List<String> FIELDS = List.of("displayName", "birthYear", "gender", "heightCm", "weightKg", "activityLevel");
    private static final List<String> GENDERS = List.of("male", "female", "other", "prefer_not_to_say");
    private static final List<String> ACTIVITIES = List.of("sedentary", "lightly_active", "moderately_active", "very_active");
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)");
    private final HealthProfileRepository profiles;
    private final Clock clock;

    public HealthProfileServiceImpl(HealthProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    @Override
    public ProfileResponses.Result read(String userId, String attemptId) {
        User user = requireUser(userId);
        ProfileResponses.Snapshot snapshot = snapshot(user);
        Object attempt = null;
        if (attemptId != null && user.getHealthProfile() != null) {
            Object receipt = user.getHealthProfile().get("lastAttempt");
            if (receipt instanceof Map<?, ?> values && attemptId.equals(values.get("attemptId"))) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", values.get("kind"));
                result.put("snapshot", snapshot);
                if (values.containsKey("applied")) result.put("applied", values.get("applied"));
                if (values.containsKey("errors")) result.put("errors", values.get("errors"));
                attempt = result;
            }
        }
        return ProfileResponses.Result.verified(snapshot, attempt);
    }

    @Override
    public ProfileResponses.Result create(String userId, ProfileRequests.Create request) {
        requireUser(userId);
        Map<String, Object> supplied = request == null || request.fields() == null ? Map.of() : request.fields();
        if (supplied.isEmpty()) {
            String decision = profiles.decide(userId, "skipped");
            return "skipped".equals(decision) ? ProfileResponses.Result.simple("skipped") : ProfileResponses.Result.conflict(snapshot(requireUser(userId)));
        }
        Validation validation = validate(supplied, false);
        if (!validation.errors().isEmpty()) return ProfileResponses.Result.invalid(validation.errors());
        String attemptId = request.attemptId() == null ? "" : request.attemptId();
        if (attemptId.length() > 128) return ProfileResponses.Result.invalid(Map.of("attemptId", "INVALID_VALUE"));
        String revision = UUID.randomUUID().toString();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", UUID.randomUUID().toString());
        profile.put("values", validation.values());
        profile.put("updatedAt", clock.instant().toString());
        profile.put("revision", revision);
        profile.put("lastAttempt", Map.of("attemptId", attemptId, "kind", "success"));
        User saved = profiles.createIfAbsent(userId, profile);
        return saved == null ? ProfileResponses.Result.conflict(snapshot(requireUser(userId))) : ProfileResponses.Result.success(snapshot(saved));
    }

    @Override
    public ProfileResponses.Result update(String userId, ProfileRequests.Update request) {
        User user = requireUser(userId);
        if (request == null || request.patch() == null || request.expectedRevision() == null) return ProfileResponses.Result.invalid(Map.of());
        ProfileResponses.Snapshot current = snapshot(user);
        if (!request.expectedRevision().equals(current.revision())) return ProfileResponses.Result.conflict(current);
        Map<String, Object> patch = request.patch();
        Map<String, Object> candidates = new LinkedHashMap<>();
        for (String field : FIELDS) if (patch.containsKey(field)) candidates.put(field, patch.get(field));
        if (candidates.size() != patch.size()) return ProfileResponses.Result.invalid(Map.of("patch", "INVALID_VALUE"));
        Validation validation = validate(candidates, true);
        Map<String, Object> existing = user.getHealthProfile() == null
                ? new LinkedHashMap<>() : values(user.getHealthProfile());
        List<String> applied = new ArrayList<>();
        for (String field : candidates.keySet()) {
            if (validation.errors().containsKey(field)) continue;
            Object value = validation.values().get(field);
            Object oldValue = existing.get(field);
            if (value == null) existing.remove(field);
            else existing.put(field, value);
            if (!java.util.Objects.equals(oldValue, value)) applied.add(field);
        }
        if (!validation.errors().isEmpty() && applied.isEmpty()) return ProfileResponses.Result.invalid(validation.errors());
        if (applied.isEmpty()) return ProfileResponses.Result.simple("no-changes");

        String attemptId = request.attemptId() == null ? "" : request.attemptId();
        if (attemptId.length() > 128) return ProfileResponses.Result.invalid(Map.of("attemptId", "INVALID_VALUE"));
        Map<String, Object> profile = user.getHealthProfile() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(user.getHealthProfile());
        profile.put("id", profile.getOrDefault("id", UUID.randomUUID().toString()));
        profile.put("values", existing);
        profile.put("updatedAt", clock.instant().toString());
        profile.put("revision", UUID.randomUUID().toString());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("attemptId", attemptId);
        receipt.put("kind", validation.errors().isEmpty() ? "success" : "partial-success");
        if (!validation.errors().isEmpty()) {
            receipt.put("applied", applied);
            receipt.put("errors", validation.errors());
        }
        profile.put("lastAttempt", receipt);
        User saved = profiles.updateIfRevision(userId, current.revision(), profile);
        if (saved == null) return ProfileResponses.Result.conflict(snapshot(requireUser(userId)));
        ProfileResponses.Snapshot savedSnapshot = snapshot(saved);
        return validation.errors().isEmpty() ? ProfileResponses.Result.success(savedSnapshot)
                : ProfileResponses.Result.partial(savedSnapshot, applied, validation.errors());
    }

    @Override
    public ProfileResponses.Result decide(String userId, ProfileRequests.Invitation request) {
        requireUser(userId);
        if (request == null || !("skipped".equals(request.decision()) || "completed".equals(request.decision())))
            return ProfileResponses.Result.invalid(Map.of("decision", "INVALID_VALUE"));
        String decision = profiles.decide(userId, request.decision());
        if (decision == null) throw new UserNotFoundException();
        if ("completed".equals(request.decision()) && !"completed".equals(decision))
            return ProfileResponses.Result.invalid(Map.of("decision", "PROFILE_REQUIRED"));
        return ProfileResponses.Result.decision(decision);
    }

    private ProfileResponses.Snapshot snapshot(User user) {
        Map<String, Object> source = user.getHealthProfile();
        if (source == null) return new ProfileResponses.Snapshot(null, "0");
        Map<String, Object> profileValues = values(source);
        ProfileResponses.Profile profile = new ProfileResponses.Profile(String.valueOf(source.get("id")), profileValues,
                String.valueOf(source.get("updatedAt")));
        return new ProfileResponses.Snapshot(profile, String.valueOf(source.get("revision")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Map<String, Object> source) {
        Object rawValues = source.get("values");
        return rawValues instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Validation validate(Map<String, Object> supplied, boolean clearingAllowed) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : supplied.entrySet()) {
            String key = field.getKey();
            Object value = field.getValue();
            if (!FIELDS.contains(key)) { errors.put(key, "INVALID_VALUE"); continue; }
            if (value == null && clearingAllowed) { values.put(key, null); continue; }
            if (("displayName".equals(key) || "gender".equals(key) || "activityLevel".equals(key))
                    && !(value instanceof String)) {
                errors.put(key, "INVALID_VALUE");
                continue;
            }
            if (("birthYear".equals(key) || "heightCm".equals(key) || "weightKg".equals(key))
                    && !(value instanceof Number || value instanceof String)) {
                errors.put(key, "INVALID_VALUE");
                continue;
            }
            String text = value == null ? "" : String.valueOf(value).strip();
            Object normalized = switch (key) {
                case "displayName" -> text.isEmpty() || text.length() > 50 ? null : text;
                case "birthYear" -> INTEGER.matcher(text).matches() && parseIntIn(text, 1900, Year.now(clock).getValue()) ? Integer.valueOf(text) : null;
                case "gender" -> GENDERS.contains(text) ? text : null;
                case "heightCm" -> parseNumber(text, 20, 300);
                case "weightKg" -> parseWeight(text);
                case "activityLevel" -> ACTIVITIES.contains(text) ? text : null;
                default -> null;
            };
            if (normalized == null) errors.put(key, "INVALID_VALUE");
            else values.put(key, normalized);
        }
        return new Validation(values, errors);
    }

    private static boolean parseIntIn(String value, int min, int max) {
        try { int parsed = Integer.parseInt(value); return parsed >= min && parsed <= max; }
        catch (NumberFormatException exception) { return false; }
    }

    private static Object parseWeight(String value) {
        return parseNumber(value, 0.3, 500);
    }

    private static Double parseNumber(String value, double min, double max) {
        if (!DECIMAL.matcher(value).matches()) return null;
        try {
            double parsed = Double.parseDouble(value.replace(',', '.'));
            return Double.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : null;
        } catch (NumberFormatException exception) { return null; }
    }

    private User requireUser(String userId) {
        User user = profiles.findUser(userId);
        if (user == null) throw new UserNotFoundException();
        return user;
    }

    private record Validation(Map<String, Object> values, Map<String, String> errors) {}
}
