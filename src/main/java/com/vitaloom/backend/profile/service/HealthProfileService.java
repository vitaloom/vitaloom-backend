package com.vitaloom.backend.profile.service;

import com.vitaloom.backend.profile.dto.ProfileRequests;
import com.vitaloom.backend.profile.dto.ProfileResponses;

public interface HealthProfileService {
    ProfileResponses.Result read(String userId, String attemptId);
    ProfileResponses.Result create(String userId, ProfileRequests.Create request);
    ProfileResponses.Result update(String userId, ProfileRequests.Update request);
    ProfileResponses.Result decide(String userId, ProfileRequests.Invitation request);
}
