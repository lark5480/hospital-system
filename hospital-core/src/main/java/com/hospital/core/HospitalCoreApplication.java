package com.hospital.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@MapperScan({
        "com.hospital.core.booking.infrastructure",
        "com.hospital.core.dispatch.infrastructure",
        "com.hospital.core.iam.infrastructure",
        "com.hospital.core.lab.infrastructure",
        "com.hospital.core.patient.infrastructure",
        "com.hospital.core.pharmacy.infrastructure",
        "com.hospital.core.platform.infrastructure",
        "com.hospital.core.report.infrastructure",
        "com.hospital.core.org.infrastructure",
        "com.hospital.core.clinical.infrastructure"
})
public class HospitalCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(HospitalCoreApplication.class, args);
    }
}
