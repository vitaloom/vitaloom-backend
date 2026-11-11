package com.vitaloom.backend.shared.persistence;

import org.springframework.data.annotation.Id;

public abstract class BaseDocument {
    @Id
    private String id;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
