package com.hospital.core.pharmacy.api;

import lombok.Data;

/**
 * 发药请求。
 * <p>药师身份由服务端从 JWT 派发,不信任客户端入参,故本 DTO 当前无字段。
 * 保留该类以维持 POST body 结构与向前兼容。
 */
@Data
public class DispenseRequest {
}
