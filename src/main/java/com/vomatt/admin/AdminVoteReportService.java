package com.vomatt.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminVoteReportResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.repository.VoteReportRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminVoteReportService {

    private final VoteReportRepository reportRepository;
    private final AdminMapper adminMapper;

    public PageResponse<AdminVoteReportResponse> listReports(Integer page, Integer limit) {
        return PageResponse.from(reportRepository.findPage(PageResponse.pageable(page, limit)),
                adminMapper::toVoteReportResponse);
    }
}
