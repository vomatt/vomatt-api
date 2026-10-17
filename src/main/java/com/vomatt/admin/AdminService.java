package com.vomatt.admin;

import java.time.OffsetDateTime;

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

    public PageResponse<AuditLogResponse> listAuditLogs(String userId, String action, String resourceType, String resourceId,
                                                        OffsetDateTime from, OffsetDateTime to, Integer page, Integer limit) {
        return PageResponse.from(adminQueryRepository.findAuditLogs(blankToNull(userId), blankToNull(action),
                blankToNull(resourceType), blankToNull(resourceId), from, to, PageResponse.pageable(page, limit)),
                adminMapper::toAuditLogResponse);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
