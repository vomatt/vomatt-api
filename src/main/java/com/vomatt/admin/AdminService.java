package com.vomatt.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminStatsResponse;
import com.vomatt.admin.dto.AuditLogResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.repository.AdminQueryRepository;

import lombok.RequiredArgsConstructor;

/** Read-only back-office views: the dashboard and the audit log. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final AdminQueryRepository adminQueryRepository;
    private final AdminMapper adminMapper;

    public AdminStatsResponse getStats() {
        return adminQueryRepository.stats(OffsetDateTime.now());
    }

    public PageResponse<AuditLogResponse> listAuditLogs(UUID userId, String action, String resourceType, UUID resourceId,
                                                        OffsetDateTime from, OffsetDateTime to, Integer page, Integer limit) {
        return PageResponse.from(adminQueryRepository.findAuditLogs(userId, blankToNull(action),
                blankToNull(resourceType), resourceId, from, to, PageResponse.pageable(page, limit)),
                adminMapper::toAuditLogResponse);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
