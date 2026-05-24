package org.linxing.linxing_agent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CatalogEntry {

    private String name;
    private String brief;
    private String whenToUse;
    private List<String> prerequisites;
}
