package com.vomatt.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminContactMessageResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.repository.ContactMessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminContactMessageService {

    private final ContactMessageRepository contactMessageRepository;
    private final AdminMapper adminMapper;

    public PageResponse<AdminContactMessageResponse> listMessages(Integer page, Integer limit) {
        return PageResponse.from(contactMessageRepository.findPage(PageResponse.pageable(page, limit)),
                adminMapper::toContactMessageResponse);
    }
}
