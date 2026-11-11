package com.vitaloom.backend.heartrate.service;

import com.vitaloom.backend.heartrate.dto.request.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.response.HeartRateBatchResponse;

public interface HeartRateService {
    HeartRateBatchResponse ingest(String accountId, HeartRateBatchRequest request);
}
