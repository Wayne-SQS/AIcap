package com.aicap.common;

import lombok.Getter;

/** 业务异常:status + detail,最终由 GlobalExceptionHandler 输出为 {"detail": "..."}(对齐 FastAPI HTTPException) */
@Getter
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public static ApiException badRequest(String detail) { return new ApiException(400, detail); }
    public static ApiException unauthorized(String detail) { return new ApiException(401, detail); }
    public static ApiException forbidden(String detail) { return new ApiException(403, detail); }
    public static ApiException notFound(String detail) { return new ApiException(404, detail); }
    public static ApiException conflict(String detail) { return new ApiException(409, detail); }
    public static ApiException unprocessable(String detail) { return new ApiException(422, detail); }
    public static ApiException server(String detail) { return new ApiException(503, detail); }
    public static ApiException notImplemented(String detail) { return new ApiException(501, detail); }
}
