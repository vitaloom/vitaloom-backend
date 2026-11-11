package com.vitaloom.backend.heartrate.service;

import com.vitaloom.backend.heartrate.dto.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.HeartRateBatchResponse;

public interface HeartRateService {
    HeartRateBatchResponse ingest(String accountId, HeartRateBatchRequest request);
}
