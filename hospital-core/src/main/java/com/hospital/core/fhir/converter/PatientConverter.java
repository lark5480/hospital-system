package com.hospital.core.fhir.converter;

import com.hospital.core.fhir.domain.FhirPatient;
import com.hospital.core.patient.domain.Patient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PatientConverter {
    
    public FhirPatient toFhir(Patient patient) {
        if (patient == null) return null;
        
        FhirPatient fhir = new FhirPatient();
        fhir.setId(String.valueOf(patient.getId()));
        
        // Identifier
        FhirPatient.Identifier internalId = new FhirPatient.Identifier();
        internalId.setSystem("http://hospital.example.com/patient-id");
        internalId.setValue(String.valueOf(patient.getId()));
        
        FhirPatient.Identifier idCard = new FhirPatient.Identifier();
        idCard.setSystem("http://www.moh.gov.cn/fhir/sid/id-card");
        idCard.setValue(patient.getIdCard());
        
        fhir.setIdentifier(List.of(internalId, idCard));
        
        // Name
        FhirPatient.HumanName name = new FhirPatient.HumanName();
        name.setFamily(patient.getName());
        name.setGiven(List.of());
        fhir.setName(List.of(name));
        
        // Gender
        if ("M".equals(patient.getGender())) {
            fhir.setGender("male");
        } else if ("F".equals(patient.getGender())) {
            fhir.setGender("female");
        }
        
        // Birth date
        if (patient.getBirthday() != null) {
            fhir.setBirthDate(patient.getBirthday().toString());
        }
        
        // Phone
        if (patient.getPhone() != null) {
            FhirPatient.ContactPoint phone = new FhirPatient.ContactPoint();
            phone.setSystem("phone");
            phone.setValue(patient.getPhone());
            fhir.setTelecom(List.of(phone));
        }
        
        return fhir;
    }
}
