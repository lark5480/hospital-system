package com.hospital.core.patient.api;

import java.time.LocalDate;

import com.hospital.core.patient.domain.Patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** C 端自助建档请求载体。 */
@Data
public class PatientRegisterRequest {

    @NotBlank(message = "姓名不能为空")
    private String name;
    @Pattern(regexp = "^[MF]$", message = "性别值无效")
    private String gender;
    private LocalDate birthday;
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
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
