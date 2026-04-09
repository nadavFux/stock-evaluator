package com.stock.analyzer.model;

import jakarta.persistence.*;
import java.util.List;

@Entity
public class Profile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String configJson; // Store SimulationRangeConfig as JSON

    public Profile() {}

    public Profile(String name, String description, String configJson) {
        this.name = name;
        this.description = description;
        this.configJson = configJson;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
