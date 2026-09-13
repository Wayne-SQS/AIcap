package com.aicap.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口:任何错误都返回 {"detail": "..."}。
 * 对齐 FastAPI:HTTPException(detail=...) 序列化为 {"detail": msg};校验失败返回 422。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String detail) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("detail", detail);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return error(HttpStatus.valueOf(e.getStatus()), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String detail = fe == null ? "请求参数校验失败"
                : "字段 " + fe.getField() + ": " + fe.getDefaultMessage();
        return error(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleUnreadable(Exception e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "请求体格式错误: " + e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "路径不存在: " + e.getResourcePath());
    }

    /** 路径存在但 HTTP 方法不支持 → 405(对齐 FastAPI,勿被兜底 Exception 吞成 500) */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /** multipart 超限 → 413(音频上传上限,与 service 内的大小校验语义一致) */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过服务端大小上限");
    }

    /** multipart 缺少 file 部件 → 422(而非 500) */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> handleMissingPart(
            org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "缺少上传部件: " + e.getRequestPartName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getMessage() == null ? "服务器内部错误" : e.getMessage());
    }
}
