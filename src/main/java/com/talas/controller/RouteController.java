package com.talas.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.talas.dto.RouteRequest;
import com.talas.dto.RouteResponse;
import com.talas.service.RouteService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** POST /api/routes/safe: a route plus, when possible, an option with fewer reported crimes nearby. */
@RestController
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @PostMapping("/api/routes/safe")
    public RouteResponse findRoutes(@Valid @RequestBody RouteRequest request) {
        return routeService.findRoutes(request);
    }
}
