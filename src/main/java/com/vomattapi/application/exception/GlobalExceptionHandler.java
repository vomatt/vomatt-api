package com.vomattapi.application.exception;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.WebRequest;

import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.ErrorType;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle Bean validation errors (e.g., password length issues)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex, WebRequest request) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        logger.debug("Validation error: {}", errors);

        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>error(ErrorType.VALIDATION_ERROR, errors)
                .withPath(request.getDescription(false));

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
    
    /**
     * Handle token refresh exceptions
     */
    @ExceptionHandler(TokenRefreshException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleTokenRefreshException(
            TokenRefreshException ex, WebRequest request) {

        logger.error("Token refresh error: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.TOKEN_REFRESH_FAILED, ex.getMessage())
                .withPath(request.getDescription(false));

        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }
    
    /**
     * Handle entity not found exceptions
     */
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFoundException(
            EntityNotFoundException ex, WebRequest request) {
        
        logger.warn("Entity not found: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.ENTITY_NOT_FOUND, ex.getMessage())
                .withPath(request.getDescription(false));
        
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }
    
    /**
     * Handle business rule violation exceptions
     */
    @ExceptionHandler(BusinessRuleViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleBusinessRuleViolationException(
            BusinessRuleViolationException ex, WebRequest request) {
        
        logger.warn("Business rule violation: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.BUSINESS_RULE_VIOLATION, ex.getMessage())
                .withPath(request.getDescription(false));
        
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
    
    /**
     * Handle unauthorized operation exceptions
     */
    @ExceptionHandler(UnauthorizedOperationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedOperationException(
            UnauthorizedOperationException ex, WebRequest request) {
        
        logger.warn("Unauthorized operation: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.UNAUTHORIZED_OPERATION, ex.getMessage())
                .withPath(request.getDescription(false));
        
        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }
    
    /**
     * Handle Spring Security access denied exceptions
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        
        logger.warn("Access denied: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.ACCESS_DENIED)
                .withPath(request.getDescription(false));
        
        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }
    
    /**
     * Handle authentication exceptions
     */
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(
            BadCredentialsException ex, WebRequest request) {
        
        logger.warn("Bad credentials: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.INVALID_CREDENTIALS)
                .withPath(request.getDescription(false));
        
        return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
    }
    
    /**
     * Handle invalid verification code exceptions
     */
    @ExceptionHandler(InvalidVerificationCodeException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleInvalidVerificationCode(
            InvalidVerificationCodeException ex, WebRequest request) {

        logger.warn("Invalid verification code: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.INVALID_VERIFICATION_CODE)
                .withPath(request.getDescription(false));

        return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Handle vote not found exceptions
     */
    @ExceptionHandler(VoteNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleVoteNotFoundException(
            VoteNotFoundException ex, WebRequest request) {

        logger.warn("Vote not found: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.VOTE_NOT_FOUND, ex.getMessage())
                .withPath(request.getDescription(false));

        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    /**
     * Handle voting not allowed exceptions
     */
    @ExceptionHandler(VotingNotAllowedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleVotingNotAllowedException(
            VotingNotAllowedException ex, WebRequest request) {

        logger.warn("Voting not allowed: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.VOTING_NOT_ALLOWED, ex.getMessage())
                .withPath(request.getDescription(false));

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle all other unhandled exceptions
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> handleAllExceptions(Exception ex, WebRequest request) {
        logger.error("Unhandled exception", ex);

        ApiResponse<Void> response = ApiResponse.<Void>error(ErrorType.INTERNAL_ERROR)
                .withPath(request.getDescription(false));

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}