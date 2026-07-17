package com.hospital.core.fhir.api;

import com.hospital.core.fhir.domain.FhirCapabilityStatement;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/fhir")
public class FhirMetadataController {

    /** GET /fhir/metadata */
    @GetMapping("/metadata")
    public ResponseEntity<FhirCapabilityStatement> getMetadata() {
        FhirCapabilityStatement cs = new FhirCapabilityStatement();
        cs.setDate(LocalDateTime.now().toString());

        FhirCapabilityStatement.RestComponent rest = new FhirCapabilityStatement.RestComponent();

        FhirCapabilityStatement.ResourceComponent patientResource = new FhirCapabilityStatement.ResourceComponent();
        patientResource.setType("Patient");
        patientResource.setInteraction(List.of(
            createInteraction("read"),
            createInteraction("search")
        ));

        FhirCapabilityStatement.ResourceComponent encounterResource = new FhirCapabilityStatement.ResourceComponent();
        encounterResource.setType("Encounter");
        encounterResource.setInteraction(List.of(
            createInteraction("read"),
            createInteraction("search")
        ));

        FhirCapabilityStatement.ResourceComponent conditionResource = new FhirCapabilityStatement.ResourceComponent();
        conditionResource.setType("Condition");
        conditionResource.setInteraction(List.of(
            createInteraction("read"),
            createInteraction("search")
        ));

        rest.setResource(List.of(patientResource, encounterResource, conditionResource));
        cs.setRest(List.of(rest));

        return ResponseEntity.ok(cs);
    }

    private FhirCapabilityStatement.InteractionComponent createInteraction(String code) {
        FhirCapabilityStatement.InteractionComponent interaction = new FhirCapabilityStatement.InteractionComponent();
        interaction.setCode(code);
        return interaction;
    }
}
