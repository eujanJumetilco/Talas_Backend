package com.talas.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talas.dto.MetaDto;
import com.talas.dto.MetaDto.CrimeTypeOption;
import com.talas.dto.MetaDto.SourceOption;
import com.talas.model.CrimeType;
import com.talas.model.Source;
import com.talas.repository.CrimeQueries;

import lombok.RequiredArgsConstructor;

/** GET /api/meta: crime types, sources and the date range of the data, for the frontend's dropdowns. */
@RestController
@RequiredArgsConstructor
public class MetaController {

    private final CrimeQueries crimeQueries;

    @GetMapping("/api/meta")
    public MetaDto meta() {
        List<CrimeTypeOption> crimeTypes = Arrays.stream(CrimeType.values())
                .map(t -> new CrimeTypeOption(t.name(), t.getLabel(), t.getWeight()))
                .toList();
        List<SourceOption> sources = Arrays.stream(Source.values())
                .map(s -> new SourceOption(s.name(), s.getLabel()))
                .toList();
        return new MetaDto(crimeTypes, sources, crimeQueries.findDateRange());
    }
}
