package com.talas.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talas.dto.HotspotDto;
import com.talas.service.HotspotService;

import lombok.RequiredArgsConstructor;

/** GET /api/hotspots: hotspot squares computed from all crimes, highest weight first. */
@RestController
@RequiredArgsConstructor
public class HotspotController {

    private final HotspotService hotspotService;

    @GetMapping("/api/hotspots")
    public List<HotspotDto> hotspots() {
        return hotspotService.findHotspots().stream().map(HotspotDto::from).toList();
    }
}
