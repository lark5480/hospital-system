package com.hospital.core.booking.api;

import lombok.Data;

/** C 端发起预约请求载体。 */
@Data
public class AppointmentRequest {

    private Long patientId;
    private Long packageId;
    private Long slotId;
}
