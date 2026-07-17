package com.hospital.core.fhir.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FhirPatient {
    @JsonProperty("resourceType")
    private String resourceType = "Patient";
    
    private String id;
    private List<Identifier> identifier;
    private List<HumanName> name;
    private String gender;
    private String birthDate;
    private List<ContactPoint> telecom;
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Identifier {
        private String system;
        private String value;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HumanName {
        private String family;
        private List<String> given;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContactPoint {
        private String system;
        private String value;
    }
}
