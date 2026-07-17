package com.hospital.core.fhir.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FhirEncounter {
    @JsonProperty("resourceType")
    private String resourceType = "Encounter";
    
    private String id;
    private String status;
    private Coding classCode;
    private List<Coding> type;
    private Reference subject;
    private List<Participant> participant;
    private Period period;
    private List<CodeableConcept> reasonCode;
    
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
    public static class Participant {
        private Reference individual;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Period {
        private String start;
        private String end;
    }
    
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeableConcept {
        private List<Coding> coding;
        private String text;
    }
}
