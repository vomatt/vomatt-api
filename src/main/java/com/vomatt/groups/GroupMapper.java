package com.vomatt.groups;

import org.springframework.stereotype.Component;

import com.vomatt.entity.Group;
import com.vomatt.entity.GroupMember;
import com.vomatt.groups.dto.GroupMemberResponse;
import com.vomatt.groups.dto.GroupResponse;

@Component
public class GroupMapper {

    public GroupResponse toResponse(Group group, long memberCount) {
        return new GroupResponse(group.getId().toString(), group.getName(), memberCount, group.getCreatedAt());
    }

    // A member added by email is shown only by that address, never revealing whether it has an account
    public GroupMemberResponse toMemberResponse(GroupMember member) {
        return member.getEmail() != null
                ? new GroupMemberResponse(null, null, null, member.getEmail(), member.getCreatedAt())
                : new GroupMemberResponse(member.getUser().getId().toString(), member.getUser().getUsername(),
                        member.getUser().getAvatarUrl(), null, member.getCreatedAt());
    }
}
