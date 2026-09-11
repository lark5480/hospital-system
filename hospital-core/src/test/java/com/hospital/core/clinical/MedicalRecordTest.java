package com.hospital.core.clinical;

import com.hospital.core.clinical.application.MedicalRecordService;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.MedicalRecord;
import com.hospital.core.clinical.domain.Visit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-49: 消除对全局种子数据(演示患者 id=1)的断言耦合。
 *
 * <p>原先三个用例硬编码 {@code patientId=1L}。现改为用 JdbcTemplate 自造测试患者并取回自增 id,
 * 测试位于 @Transactional 事务中、结束即回滚,不再依赖 schema.sql / DataInitializer 播下的种子数据。
 */
@SpringBootTest
@Transactional
class MedicalRecordTest {

    @Autowired
    private MedicalRecordService medicalRecordService;

    @Autowired
    private VisitService visitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** R-49: 自造测试患者,返回自增 id(独立手机号,与种子数据隔离;事务结束回滚)。 */
    private Long newTestPatientId() {
        String phone = "13900000051";
        return jdbcTemplate.queryForObject(
                "INSERT INTO patient.patient (name, gender, birthday, phone, username) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "R49病历测试患者", "M", Date.valueOf("1990-01-01"), phone, phone);
    }

    /**
     * R-49: 自造测试医生。注意 clinical.medical_record.doctor_id 为 NOT NULL,
     * MedicalRecordService.save 会用就诊的 doctor_id 回填,故这里的就诊必须带一个非空医生 id。
     */
    private Long newTestDoctorId() {
        String phone = "13900000052";
        return jdbcTemplate.queryForObject(
                "INSERT INTO org.staff (name, gender, phone, dept_id, position, username) "
                        + "VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, "R49病历测试医生", "M", phone, null, "DOCTOR", phone);
    }

    private Visit newVisit(Long patientId, Long doctorId, String chiefComplaint) {
        Visit visit = new Visit();
        visit.setPatientId(patientId);
        visit.setDoctorId(doctorId);
        visit.setChiefComplaint(chiefComplaint);
        return visitService.create(visit);
    }

    @Test
    void shouldCreateMedicalRecord() {
        // Given
        Visit created = newVisit(newTestPatientId(), newTestDoctorId(), "头痛、发热");

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
        Visit created = newVisit(newTestPatientId(), newTestDoctorId(), "测试");

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
        // Given - 用自造患者 id 查询,不依赖种子患者 id=1
        Long patientId = newTestPatientId();
        Visit created = newVisit(patientId, newTestDoctorId(), "测试");

        MedicalRecord record = new MedicalRecord();
        record.setChiefComplaint("测试主诉");
        medicalRecordService.save(created.getId(), record);

        // When
        List<MedicalRecord> records = medicalRecordService.listByPatientId(patientId);

        // Then
        assertThat(records).isNotEmpty();
    }
}
