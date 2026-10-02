package com.vomatt.lookups;

import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.entity.Lookup;
import org.springframework.stereotype.Component;

@Component
public class LookupMapper {

    public LookupDto toDto(Lookup lookup) {
        return new LookupDto(
                lookup.getId().toString(),
                lookup.getLookupType(),
                lookup.getLookupKey(),
                lookup.getLookupValue(),
                lookup.getSeq(),
                lookup.getParentType(),
                lookup.getParentKey(),
                lookup.getIsActive(),
                lookup.getDescription(),
                lookup.getFrontendUsing()
        );
    }
}
