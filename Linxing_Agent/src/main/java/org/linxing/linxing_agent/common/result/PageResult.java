package org.linxing.linxing_agent.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;

    private long total;

    private int page;

    private int size;

    private int totalPages;

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        int totalPages = (int) Math.ceil((double) total / size);
        if (totalPages < 1) {
            totalPages = 1;
        }
        return PageResult.<T>builder()
                .records(records)
                .total(total)
                .page(page)
                .size(size)
                .totalPages(totalPages)
                .build();
    }
}
