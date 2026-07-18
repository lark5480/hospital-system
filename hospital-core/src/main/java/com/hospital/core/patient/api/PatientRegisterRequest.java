package com.hospital.core.patient.api;

import com.hospital.core.patient.domain.Patient;
import lombok.Data;

import java.time.LocalDate;

/** C 端自助建档请求载体。 */
@Data
public class PatientRegisterRequest {

    private String name;
    private String gender;
    private LocalDate birthday;
    private String phone;
    private String idCard;
    private String username;

    public Patient toDomain() {
        Patient p = new Patient();
        p.setName(name);
        p.setGender(gender);
        p.setBirthday(birthday);
        p.setPhone(phone);
        p.setIdCard(idCard);
        p.setUsername(username);
        return p;
    }
}
