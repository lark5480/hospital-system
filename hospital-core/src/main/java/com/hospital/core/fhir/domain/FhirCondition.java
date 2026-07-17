package com.hospital.core.fhir.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FhirCondition {
    @JsonProperty("resourceType")
    private String resourceType = "Condition";
    
    private String id;
    private Coding clinicalStatus;
    private Coding verificationStatus;
    private List<Coding> category;
    private CodeableConcept code;
    private Reference subject;
    private Reference encounter;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Coding {
        private String system;
        private String code;
        private String display;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Reference {
        private String reference;
        private String display;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeableConcept {
        private List<Coding> coding;
        private String text;
    }
}
