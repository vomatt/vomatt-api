package com.vomatt.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminCommentReportResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.repository.CommentReportRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCommentReportService {

    private final CommentReportRepository reportRepository;
    private final AdminMapper adminMapper;

    public PageResponse<AdminCommentReportResponse> listReports(Integer page, Integer limit) {
        return PageResponse.from(reportRepository.findPage(PageResponse.pageable(page, limit)),
                adminMapper::toCommentReportResponse);
    }
}
