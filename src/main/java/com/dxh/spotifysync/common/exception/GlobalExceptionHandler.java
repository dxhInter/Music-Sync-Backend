package com.dxh.spotifysync.common.exception;

import com.dxh.spotifysync.common.api.CommonResult;
import com.dxh.spotifysync.common.api.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 全局异常处理
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ResponseBody
    @ExceptionHandler(value = ApiException.class)
    public ResponseEntity<CommonResult> handle(ApiException e) {
        if (e.getErrorCode() != null) {
            return ResponseEntity.status(resolveStatus(e.getErrorCode().getCode()))
                    .body(CommonResult.failed(e.getErrorCode(), e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResult.failed(ResultCode.BAD_REQUEST, e.getMessage()));
    }

    @ResponseBody
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResult> handleValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = null;
        if (bindingResult.hasErrors()) {
            FieldError fieldError = bindingResult.getFieldError();
            if (fieldError != null) {
                message = fieldError.getField()+fieldError.getDefaultMessage();
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResult.validateFailed(message));
    }

    @ResponseBody
    @ExceptionHandler(value = BindException.class)
    public ResponseEntity<CommonResult> handleValidException(BindException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = null;
        if (bindingResult.hasErrors()) {
            FieldError fieldError = bindingResult.getFieldError();
            if (fieldError != null) {
                message = fieldError.getField()+fieldError.getDefaultMessage();
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResult.validateFailed(message));
    }

    private HttpStatus resolveStatus(long code) {
        if (code == 400) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == 401) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == 403) {
            return HttpStatus.FORBIDDEN;
        }
        if (code == 404) {
            return HttpStatus.NOT_FOUND;
        }
        if (code == 409) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
