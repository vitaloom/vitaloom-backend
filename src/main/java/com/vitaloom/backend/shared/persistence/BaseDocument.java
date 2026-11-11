package com.vitaloom.backend.shared.persistence;

import org.springframework.data.annotation.Id;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class BaseDocument {
    @Id
    private String id;
}
