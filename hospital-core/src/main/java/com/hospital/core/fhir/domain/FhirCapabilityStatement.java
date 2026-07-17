package com.hospital.core.fhir.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FhirCapabilityStatement {
    @JsonProperty("resourceType")
    private String resourceType = "CapabilityStatement";
    
    private String status = "active";
    private String date;
    private String kind = "instance";
    private String fhirVersion = "4.0.1";
    private List<String> format = List.of("json");
    private List<RestComponent> rest;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RestComponent {
        private String mode = "server";
        private List<ResourceComponent> resource;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResourceComponent {
        private String type;
        private List<InteractionComponent> interaction;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InteractionComponent {
        private String code;
    }
}
