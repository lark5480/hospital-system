package com.hospital.core.clinical;

import com.hospital.core.clinical.application.MedicalRecordService;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.MedicalRecord;
import com.hospital.core.clinical.domain.Visit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MedicalRecordTest {

    @Autowired
    private MedicalRecordService medicalRecordService;

    @Autowired
    private VisitService visitService;

    @Test
    void shouldCreateMedicalRecord() {
        // Given
        Visit visit = new Visit();
        visit.setPatientId(1L);
        visit.setDoctorId(1L);
        visit.setDeptId(1L);
        visit.setChiefComplaint("头痛、发热");
        Visit created = visitService.create(visit);

        MedicalRecord record = new MedicalRecord();
        record.setChiefComplaint("头痛、发热3天");
        record.setPresentIllness("患者3天前无明显诱因出现头痛，伴有发热，体温最高38.5°C");
        record.setPastHistory("既往体健");
        record.setPhysicalExam(Map.of(
                "temperature", "38.2°C",
                "pulse", "92次/分",
                "bloodPressure", "120/80mmHg"
        ));
        record.setDiagnosis(List.of(
                Map.of("code", "J06.9", "name", "急性上呼吸道感染", "type", "主诊断")
        ));

        // When
        MedicalRecord saved = medicalRecordService.save(created.getId(), record);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVisitId()).isEqualTo(created.getId());
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getPhysicalExam()).containsKey("temperature");
    }

    @Test
    void shouldFinalizeMedicalRecord() {
        // Given
        Visit visit = new Visit();
        visit.setPatientId(1L);
        visit.setDoctorId(1L);
        visit.setDeptId(1L);
        visit.setChiefComplaint("测试");
        Visit created = visitService.create(visit);

        MedicalRecord record = new MedicalRecord();
        record.setChiefComplaint("测试主诉");
        medicalRecordService.save(created.getId(), record);

        // When
        MedicalRecord finalized = medicalRecordService.finalize(created.getId());

        // Then
        assertThat(finalized.getStatus()).isEqualTo("FINAL");
        assertThat(finalized.getFinalizedAt()).isNotNull();
    }

    @Test
    void shouldQueryByPatientId() {
        // Given
        Visit visit = new Visit();
        visit.setPatientId(1L);
        visit.setDoctorId(1L);
        visit.setDeptId(1L);
        visit.setChiefComplaint("测试");
        Visit created = visitService.create(visit);

        MedicalRecord record = new MedicalRecord();
        record.setChiefComplaint("测试主诉");
        medicalRecordService.save(created.getId(), record);

        // When
        List<MedicalRecord> records = medicalRecordService.listByPatientId(1L);

        // Then
        assertThat(records).isNotEmpty();
    }
}
