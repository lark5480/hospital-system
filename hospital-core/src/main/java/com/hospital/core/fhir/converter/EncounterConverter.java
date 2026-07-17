package com.hospital.core.fhir.converter;

import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.domain.FhirEncounter;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EncounterConverter {
    
    private final StaffService staffService;
    private final DepartmentService departmentService;
    
    public FhirEncounter toFhir(Visit visit) {
        if (visit == null) return null;
        
        FhirEncounter fhir = new FhirEncounter();
        fhir.setId(String.valueOf(visit.getId()));
        
        // Status mapping
        fhir.setStatus(mapVisitStatus(visit.getStatus()));
        
        // Class: ambulatory
        FhirEncounter.Coding classCode = new FhirEncounter.Coding();
        classCode.setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode");
        classCode.setCode("AMB");
        classCode.setDisplay("ambulatory");
        fhir.setClassCode(classCode);
        
        // Type: outpatient
        FhirEncounter.Coding typeCode = new FhirEncounter.Coding();
        typeCode.setSystem("http://example.com/visit-type");
        typeCode.setCode("OUTPATIENT");
        typeCode.setDisplay("门诊");
        fhir.setType(List.of(typeCode));
        
        // Subject (patient reference)
        FhirEncounter.Reference subject = new FhirEncounter.Reference();
        subject.setReference("Patient/" + visit.getPatientId());
        fhir.setSubject(subject);
        
        // Participant (doctor)
        if (visit.getDoctorId() != null) {
            var doctor = staffService.get(visit.getDoctorId());
            if (doctor != null) {
                FhirEncounter.Participant participant = new FhirEncounter.Participant();
                FhirEncounter.Reference individual = new FhirEncounter.Reference();
                individual.setReference("Practitioner/" + doctor.getId());
                individual.setDisplay(doctor.getName());
                participant.setIndividual(individual);
                fhir.setParticipant(List.of(participant));
            }
        }
        
        // Period
        if (visit.getVisitTime() != null) {
            FhirEncounter.Period period = new FhirEncounter.Period();
            period.setStart(visit.getVisitTime().toString());
            fhir.setPeriod(period);
        }
        
        // Reason code (chief complaint)
        if (visit.getChiefComplaint() != null) {
            FhirEncounter.CodeableConcept reason = new FhirEncounter.CodeableConcept();
            reason.setText(visit.getChiefComplaint());
            fhir.setReasonCode(List.of(reason));
        }
        
        return fhir;
    }
    
    private String mapVisitStatus(String status) {
        if (status == null) return "unknown";
        return switch (status) {
            case "CREATED", "CONFIRMED" -> "planned";
            case "IN_PROGRESS" -> "in-progress";
            case "FINISHED" -> "finished";
            default -> "unknown";
        };
    }
}
