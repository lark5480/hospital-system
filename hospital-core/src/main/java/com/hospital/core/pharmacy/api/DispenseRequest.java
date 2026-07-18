package com.hospital.core.pharmacy.api;

import lombok.Data;

/**
 * 发药请求。
 * <p>药师身份由服务端从 JWT 派发,不信任客户端入参。
 */
@Data
public class DispenseRequest {
    /** 用药注意事项/备注 */
    private String remark;
}
