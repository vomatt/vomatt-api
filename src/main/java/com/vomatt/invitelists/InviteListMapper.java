package com.vomatt.invitelists;

import org.springframework.stereotype.Component;

import com.vomatt.entity.InviteList;
import com.vomatt.entity.InviteListMember;
import com.vomatt.invitelists.dto.InviteListMemberResponse;
import com.vomatt.invitelists.dto.InviteListResponse;

@Component
public class InviteListMapper {

    public InviteListResponse toResponse(InviteList inviteList, long memberCount) {
        return new InviteListResponse(inviteList.getId().toString(), inviteList.getName(), memberCount, inviteList.getCreatedAt());
    }

    // A member added by email is shown only by that address, never revealing whether it has an account
    public InviteListMemberResponse toMemberResponse(InviteListMember member) {
        return member.getEmail() != null
                ? new InviteListMemberResponse(null, null, null, member.getEmail(), member.getCreatedAt())
                : new InviteListMemberResponse(member.getUser().getId().toString(), member.getUser().getUsername(),
                        member.getUser().getAvatarUrl(), null, member.getCreatedAt());
    }
}
