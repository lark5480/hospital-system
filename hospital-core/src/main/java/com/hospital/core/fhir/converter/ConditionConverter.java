package com.hospital.core.fhir.converter;

import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.domain.FhirCondition;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConditionConverter {
    
    public FhirCondition toFhir(Visit visit) {
        if (visit == null || visit.getChiefComplaint() == null) return null;
        
        FhirCondition fhir = new FhirCondition();
        fhir.setId(String.valueOf(visit.getId()));
        
        // Clinical status: active
        FhirCondition.Coding clinicalStatus = new FhirCondition.Coding();
        clinicalStatus.setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical");
        clinicalStatus.setCode("active");
        fhir.setClinicalStatus(clinicalStatus);
        
        // Verification status: confirmed
        FhirCondition.Coding verificationStatus = new FhirCondition.Coding();
        verificationStatus.setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status");
        verificationStatus.setCode("confirmed");
        fhir.setVerificationStatus(verificationStatus);
        
        // Category: encounter-diagnosis
        FhirCondition.Coding categoryCode = new FhirCondition.Coding();
        categoryCode.setSystem("http://terminology.hl7.org/CodeSystem/condition-category");
        categoryCode.setCode("encounter-diagnosis");
        categoryCode.setDisplay("Encounter Diagnosis");
        fhir.setCategory(List.of(categoryCode));
        
        // Code: chief complaint
        FhirCondition.CodeableConcept code = new FhirCondition.CodeableConcept();
        code.setText(visit.getChiefComplaint());
        fhir.setCode(code);
        
        // Subject (patient reference)
        FhirCondition.Reference subject = new FhirCondition.Reference();
        subject.setReference("Patient/" + visit.getPatientId());
        fhir.setSubject(subject);
        
        // Encounter reference
        FhirCondition.Reference encounter = new FhirCondition.Reference();
        encounter.setReference("Encounter/" + visit.getId());
        fhir.setEncounter(encounter);
        
        return fhir;
    }
}
