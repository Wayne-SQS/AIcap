package com.aicap.common;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /** 主键冲突 → 409(编号分配竞态)。
     *
     *  <p>故事 / 需求池的编号来自「扫描当前最大号 +1」({@code com.aicap.service.IdAllocator}),
     *  两个并发创建仍可能算出同一个号;{@code id} 是 varchar 主键,后者插入即冲突。
     *  此前该冲突落到下面的兜底 {@code handleOther},客户端拿到的是
     *  <b>500「服务器内部错误」</b> —— 既不知道原因、也无法安全重试。
     *  这里显式映射为 409 + 可操作文案,与 IdAllocator 的「插入前复查 + 顺延」共同构成
     *  「并发创建只会成功或明确 409,绝不出 500」这一保证(见 StoryContractTest 并发用例)。 */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateKey(
            org.springframework.dao.DuplicateKeyException e) {
        log.warn("主键冲突,已返回 409(编号分配竞态): {}", e.getClass().getName());
        return error(HttpStatus.CONFLICT, "编号已被占用（并发创建冲突），请重试");
    }

    /** 未预期的异常 → 500。
     *
     *  <p>**必须记日志 + 不回显内部消息**:原先直接把 {@code e.getMessage()} 当作 detail 返回,
     *  而 JDBC/MyBatis 的约束冲突消息里带表名、列名、约束名甚至部分 SQL,等于把内部结构
     *  泄露给客户端;同时该方法此前**没有任何日志**,导致客户端拿到堆栈文本、服务端却查不到
     *  任何记录。与 AgentModelFixture 的 canary 断言(上游文案不得外泄)同一原则。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        log.error("未处理异常,已返回 500: {}", e.getClass().getName(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }
}
