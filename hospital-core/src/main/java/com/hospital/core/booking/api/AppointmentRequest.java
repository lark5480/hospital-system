package com.hospital.core.booking.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** C 端发起预约请求载体。 */
@Data
public class AppointmentRequest {

    @NotNull(message = "患者ID不能为空")
    private Long patientId;
    @NotNull(message = "套餐ID不能为空")
    private Long packageId;
    @NotNull(message = "号源ID不能为空")
    private Long slotId;
}
